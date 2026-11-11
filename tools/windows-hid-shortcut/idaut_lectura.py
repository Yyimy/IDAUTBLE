"""Windows companion for an already assigned PLC Scanner HID receiver."""
import asyncio
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import queue
import re
import sys
import threading
from types import SimpleNamespace


FFE1 = "0000ffe1-0000-1000-8000-00805f9b34fb"
QUERY = b"HIDHOTKEY?\r\n"
READ = b"HIDREAD\r\n"
HOTKEY = re.compile(r"(?:(CTRL\+)(ALT\+)?|(ALT\+))(SHIFT\+)?([A-Z0-9]|F(?:[1-9]|1[0-2]))")
WM_HOTKEY = 0x0312


def hotkey_codes(value):
    match = HOTKEY.fullmatch(value)
    if match is None:
        raise ValueError("Atajo inválido")
    ctrl, alt, alt_only, shift, key = match.groups()
    modifiers = 0x4000 | (2 if ctrl else 0) | (1 if alt or alt_only else 0) | (4 if shift else 0)
    return modifiers, ord(key) if len(key) == 1 else 0x6F + int(key[1:])


class Hotkey:
    def __init__(self, user32):
        self.user32 = user32
        self.active = "NONE"
        self.id = None

    def apply(self, value):
        if value == self.active:
            return True
        old = self.id
        new = None
        if value != "NONE":
            flags, key = hotkey_codes(value)
            new = 2 if old == 1 else 1
            if not self.user32.RegisterHotKey(None, new, flags, key):
                return False
        if old is not None:
            self.user32.UnregisterHotKey(None, old)
        self.id, self.active = new, value
        return True

    def pressed(self):
        message = wintypes.MSG()
        pressed = False
        while self.user32.PeekMessageW(ctypes.byref(message), None, WM_HOTKEY, WM_HOTKEY, 1):
            pressed = pressed or message.wParam == self.id
        return pressed


class Connection:
    def __init__(self, emit, last_device):
        self.emit = emit
        self.last_device = last_device
        self.transition = asyncio.Lock()
        self.hotkey = Hotkey(ctypes.windll.user32)
        self.client = None
        self.sync = None
        self.received = asyncio.Event()
        self.buffer = bytearray()
        self.reading = False

    async def paired(self, reconnect=False):
        from winrt.windows.devices.bluetooth import BluetoothLEDevice
        from winrt.windows.devices.enumeration import DeviceInformation, DeviceInformationKind

        selector = BluetoothLEDevice.get_device_selector_from_pairing_state(True)
        try:
            found = await asyncio.wait_for(DeviceInformation.find_all_async_with_kind_aqs_filter_and_additional_properties(
                selector, [], DeviceInformationKind.ASSOCIATION_ENDPOINT), timeout=10)
            devices = [(item.id, item.name or "Sin nombre") for item in found]
            try:
                saved = self.last_device.read_text(encoding="utf-8")
            except (OSError, UnicodeError):
                saved = None
            self.emit("devices", (devices, saved))
            self.emit("status", "Selecciona el PLC emparejado en Windows.")
            if reconnect:
                target = next((device for device in devices if device[0] == saved), None)
                if target is not None:
                    await self.connect(*target)
        except Exception as exc:
            self.emit("status", f"Error al consultar Bluetooth: {exc}")

    async def connect(self, device_id, name):
        async with self.transition:
            if self.client is None:
                await self._connect(device_id, name)

    async def _connect(self, device_id, name):
        from bleak import BleakClient
        from bleak.backends.device import BLEDevice
        from bleak.backends.winrt.scanner import RawAdvData
        from winrt.windows.devices.bluetooth import BluetoothAddressType, BluetoothLEDevice

        self.emit("link", "busy")
        self.emit("status", f"Conectando a {name}…")
        try:
            device = await asyncio.wait_for(BluetoothLEDevice.from_id_async(device_id), timeout=10)
            if device is None:
                raise RuntimeError("Windows no encuentra el emparejamiento")
            try:
                address = device.bluetooth_address
                target = BLEDevice(address.to_bytes(6, "big").hex(":"), name,
                                   RawAdvData(adv=SimpleNamespace(bluetooth_address=address), scan=None))
                address_type = "random" if device.bluetooth_address_type == BluetoothAddressType.RANDOM else "public"
            finally:
                device.close()
            client = BleakClient(target, timeout=15, winrt={"address_type": address_type, "use_cached_services": False},
                                 disconnected_callback=self.lost)
            self.client = client
            await client.connect()
            await client.start_notify(FFE1, lambda _, data: self.notification(client, data))
            await self.refresh(client)
            if self.client is not client:
                raise RuntimeError("se perdió el enlace Bluetooth")
            self.sync = asyncio.create_task(self.keep_in_sync(client))
            self.emit("link", "connected")
            try:
                self.last_device.parent.mkdir(parents=True, exist_ok=True)
                self.last_device.write_text(device_id, encoding="utf-8")
            except OSError:
                self.emit("status", "Conectado. Windows no ha podido guardar el PLC para la próxima apertura.")
        except asyncio.CancelledError:
            await self._disconnect()
            raise
        except Exception as exc:
            await self._disconnect()
            self.emit("status", f"No se pudo abrir el canal de lectura: {exc}. Comprueba que este PC está asignado como HID.")

    def lost(self, client):
        if self.client is not client:
            return
        self.clear_link()
        if not self.transition.locked():
            self.emit("link", "idle")
            self.emit("status", "Desconectado del PLC.")

    def clear_link(self):
        self.client = None
        self.received.clear()
        self.buffer.clear()
        self.hotkey.apply("NONE")
        self.emit("hotkey", "—")

    async def disconnect(self):
        async with self.transition:
            await self._disconnect()

    async def _disconnect(self):
        self.emit("link", "busy")
        client = self.client
        if self.sync is not None and self.sync is not asyncio.current_task():
            self.sync.cancel()
        self.sync = None
        self.clear_link()
        try:
            if client is not None:
                await client.disconnect()
            self.emit("status", "Desconectado del PLC.")
        except Exception as exc:
            self.emit("status", f"Error al cerrar Bluetooth: {exc}")
        finally:
            self.emit("link", "idle")

    async def refresh(self, client):
        self.received.clear()
        await client.write_gatt_char(FFE1, QUERY, response=True)
        try:
            await asyncio.wait_for(self.received.wait(), timeout=5)
        except TimeoutError as exc:
            raise RuntimeError("el PLC no confirmó el atajo") from exc

    async def keep_in_sync(self, client):
        while self.client is client:
            await asyncio.sleep(15)
            if self.client is not client:
                return
            try:
                await self.refresh(client)
            except Exception as exc:
                async with self.transition:
                    if self.client is client:
                        await self._disconnect()
                        self.emit("status", f"Canal de lectura cerrado: {exc}.")

    def notification(self, client, data):
        if client is not self.client:
            return
        self.buffer.extend(data)
        while (end := self.buffer.find(b"\r\n")) >= 0:
            line = bytes(self.buffer[:end])
            del self.buffer[:end + 2]
            if line.startswith(b"\x06HIDHOTKEY="):
                try:
                    value = line.split(b"=", 1)[1].decode("ascii")
                    applied = self.hotkey.apply(value)
                except ValueError:
                    continue
                self.received.set()
                self.emit("hotkey", self.hotkey.active if self.hotkey.active != "NONE" else "Desactivado")
                self.emit("status", "Conectado al receptor HID." if applied else f"{value} está ocupado en Windows. Se conserva el atajo anterior.")
            elif line == b"\x06HIDREAD=QUEUED":
                self.emit("status", "Lectura solicitada.")
            elif line.startswith(b"\x15"):
                self.emit("status", f"PLC: {line[1:].decode('ascii', errors='replace')}")
        if len(self.buffer) > 256:
            self.buffer.clear()

    async def poll_keys(self):
        while True:
            if self.hotkey.pressed() and self.client and self.received.is_set() and not self.reading:
                asyncio.create_task(self.request_read(self.client))
            await asyncio.sleep(0.05)

    async def request_read(self, client):
        self.reading = True
        try:
            shortcut = self.hotkey.active
            if shortcut == "NONE":
                return
            _, key = hotkey_codes(shortcut)
            # The PLC types on the same PC. Wait for the physical modifiers to
            # be released so Windows does not apply them to the HID output.
            while any(self.hotkey.user32.GetAsyncKeyState(vk) & 0x8000 for vk in (0x10, 0x11, 0x12, key)):
                await asyncio.sleep(0.05)
                if self.client is not client or self.hotkey.active != shortcut:
                    return
            if self.client is not client or self.hotkey.active != shortcut:
                return
            await client.write_gatt_char(FFE1, READ, response=True)
        except Exception as exc:
            self.emit("status", f"Error al solicitar la lectura: {exc}")
        finally:
            self.reading = False


def main():
    if sys.platform != "win32":
        raise SystemExit("IDAUT Lectura requiere Windows.")
    import tkinter as tk
    from tkinter import ttk
    import pystray
    from PIL import Image, ImageDraw

    events = queue.SimpleQueue()
    emit = lambda kind, value=None: events.put((kind, value))
    loop = asyncio.new_event_loop()
    settings = Path(os.environ.get("LOCALAPPDATA") or Path.home()) / "IDAUT Lectura"
    connection = Connection(emit, settings / "last-device.txt")

    def worker():
        asyncio.set_event_loop(loop)
        loop.create_task(connection.poll_keys())
        loop.run_forever()

    threading.Thread(target=worker, daemon=True).start()

    def run(coro):
        return asyncio.run_coroutine_threadsafe(coro, loop)

    root = tk.Tk()
    root.title("IDAUT Lectura")
    frame = ttk.Frame(root, padding=12)
    frame.grid(sticky="nsew")
    devices = []
    picker = ttk.Combobox(frame, state="readonly", width=35)
    picker.grid(row=0, column=0, columnspan=2, sticky="ew")
    status = tk.StringVar(value="Buscando dispositivos emparejados…")
    hotkey = tk.StringVar(value="—")
    reload_button = ttk.Button(frame, text="Emparejados", command=lambda: run(connection.paired()))
    reload_button.grid(row=1, column=0, sticky="ew", pady=6)
    link = "idle"
    pending_connection = None

    def toggle():
        nonlocal pending_connection
        if link == "connected":
            button.configure(state="disabled")
            run(connection.disconnect())
        elif link == "idle" and picker.current() >= 0:
            button.configure(state="disabled")
            pending_connection = run(connection.connect(*devices[picker.current()]))

    button = ttk.Button(frame, text="Conectar", command=toggle, state="disabled")
    button.grid(row=1, column=1, sticky="ew", pady=6)
    ttk.Label(frame, text="Atajo activo").grid(row=2, column=0, sticky="w")
    ttk.Label(frame, textvariable=hotkey).grid(row=2, column=1, sticky="w")
    ttk.Label(frame, textvariable=status, wraplength=320).grid(row=3, column=0, columnspan=2, sticky="w", pady=6)
    ttk.Button(frame, text="Ocultar en la bandeja", command=root.withdraw).grid(row=4, column=0, columnspan=2)

    image = Image.new("RGB", (64, 64), "#164465")
    ImageDraw.Draw(image).text((24, 24), "L", fill="white")
    tray = pystray.Icon("idaut-lectura", image, "IDAUT Lectura", pystray.Menu(
        pystray.MenuItem("Abrir", lambda: emit("show"), default=True),
        pystray.MenuItem("Salir", lambda: emit("quit")),
    ))
    threading.Thread(target=tray.run, daemon=True).start()
    root.protocol("WM_DELETE_WINDOW", root.withdraw)

    def update():
        nonlocal devices, link
        while not events.empty():
            kind, value = events.get()
            if kind == "devices":
                current = picker.current()
                previous = devices[current][0] if 0 <= current < len(devices) else None
                devices, saved = value
                picker["values"] = [f"{name} ({i + 1})" for i, (_, name) in enumerate(devices)]
                picker.set("")
                selected = next((i for i, (device_id, _) in enumerate(devices)
                                 if device_id == (previous or saved)), None)
                if selected is not None:
                    picker.current(selected)
                elif len(devices) == 1:
                    picker.current(0)
                if link == "idle":
                    button.configure(state="normal")
            elif kind == "status":
                status.set(value)
            elif kind == "hotkey":
                hotkey.set(value)
            elif kind == "link":
                link = value
                button.configure(text="Desconectar" if link == "connected" else "Conectar",
                                 state="disabled" if link == "busy" else "normal")
                reload_button.configure(state="normal" if link == "idle" else "disabled")
                picker.configure(state="readonly" if link == "idle" else "disabled")
            elif kind == "show":
                root.deiconify()
                root.lift()
            elif kind == "quit":
                root.destroy()
                return
        root.after(80, update)

    startup = run(connection.paired(reconnect=True))
    root.after(80, update)
    try:
        root.mainloop()
    finally:
        try:
            startup.cancel()
            if pending_connection is not None:
                pending_connection.cancel()
            run(connection.disconnect()).result(timeout=3)
        finally:
            loop.call_soon_threadsafe(loop.stop)
            tray.stop()


if __name__ == "__main__":
    main()
