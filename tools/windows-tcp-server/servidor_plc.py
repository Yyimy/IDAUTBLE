"""Servidor TCP de pruebas para IDAUTBLE. Solo usa la biblioteca estándar."""
import datetime
import ipaddress
import os
from pathlib import Path
import queue
import re
import select
import socket
import sqlite3
import threading
import tkinter as tk
from tkinter import messagebox, ttk


class Decoder:
    def __init__(self):
        self.buffer = bytearray()

    def feed(self, data):
        self.buffer.extend(data)
        while self.buffer:
            newline = self.buffer.find(b"\n")
            binary = self.buffer[0] == 6 and len(self.buffer) > 20 and self.buffer[20] == 2
            end = self.buffer.find(b"\x03", 21) if binary else newline
            if end < 0:
                if len(self.buffer) > 8192:
                    raise ValueError("Mensaje demasiado largo o sin terminador.")
                return
            record = bytes(self.buffer[:end + 1])
            del self.buffer[:end + 1]
            yield binary, record if binary else record.rstrip(b"\r\n")


def reading(frame):
    timestamp = frame[1:20].decode("ascii")
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}", timestamp):
        raise ValueError("El campo RTC no tiene el formato de la trama V2.")
    parts = frame[21:-1].decode("ascii").split("\x1b")
    if len(parts) != 7 or parts[-1] or not parts[0] or any(
        not 32 <= ord(char) <= 126 for field in parts for char in field
    ):
        raise ValueError("Trama V2 incompleta o inválida.")
    return timestamp, parts[:-1]


def validate_line(value):
    if not value or len(value) > 240 or any(not 32 <= ord(char) <= 126 for char in value):
        raise ValueError("Usa entre 1 y 240 caracteres ASCII imprimibles por línea.")
    return value


def open_database(path):
    db = sqlite3.connect(path)
    db.execute("""CREATE TABLE IF NOT EXISTS eventos (
        plc TEXT NOT NULL, evento INTEGER NOT NULL, trama BLOB NOT NULL,
        recibido TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (plc, evento))""")
    return db


def save_event(db, device, event, frame):
    with db:
        previous = db.execute(
            "SELECT trama FROM eventos WHERE plc=? AND evento=?", (device, event)
        ).fetchone()
        if previous:
            return "repetido" if previous[0] == frame else "conflicto"
        db.execute("INSERT INTO eventos (plc, evento, trama) VALUES (?, ?, ?)", (device, event, frame))
    return "guardado"


class Server:
    def __init__(self, bind, port, database, log, options):
        self.log = log
        self.options = options
        self.database = database
        self.stopped = threading.Event()
        self.manual = queue.Queue(maxsize=20)
        self.peer = None
        self.listener = socket.socket()
        try:
            self.listener.bind((bind, port))
            self.listener.listen(1)
            self.listener.settimeout(0.25)
        except OSError:
            self.listener.close()
            raise

    def stop(self):
        self.stopped.set()
        self.listener.close()

    def send_manual(self, line):
        peer = self.peer
        if peer is None:
            raise ValueError("Todavía no hay un PLC conectado.")
        try:
            self.manual.put_nowait((peer, line))
        except queue.Full:
            raise ValueError("Espera a que se envíen las líneas pendientes.") from None

    def send(self, peer, line):
        peer.sendall(line.encode("ascii") + b"\r\n")
        self.log("TX", line)

    def run(self):
        try:
            db = open_database(self.database)
            try:
                while not self.stopped.is_set():
                    try:
                        peer, address = self.listener.accept()
                    except socket.timeout:
                        continue
                    except OSError:
                        if self.stopped.is_set():
                            break
                        raise
                    with peer:
                        peer.settimeout(0.25)
                        self.peer = peer
                        self.log("TCP", f"Conectado {address[0]}:{address[1]}")
                        try:
                            self.handle(peer, db)
                        except (OSError, ValueError) as error:
                            if not self.stopped.is_set():
                                self.log("ERROR", str(error))
                        finally:
                            self.peer = None
                            self.log("TCP", f"Desconectado {address[0]}:{address[1]}")
            finally:
                db.close()
        except (OSError, sqlite3.Error) as error:
            self.log("ERROR", str(error))
        finally:
            self.stop()
            self.log("TCP", "Servidor detenido.")

    def handle(self, peer, db):
        event = None
        decoder = Decoder()
        while not self.stopped.is_set():
            try:
                while True:
                    target, line = self.manual.get_nowait()
                    if target is peer:
                        self.send(peer, line)
            except queue.Empty:
                pass
            try:
                readable, _, _ = select.select((self.listener, peer), (), (), 0.25)
            except (OSError, ValueError):
                if self.stopped.is_set():
                    return
                raise
            if self.stopped.is_set():
                return
            if self.listener in readable:
                self.log("TCP", "Sustituida la conexión anterior por una nueva.")
                return
            if peer not in readable:
                continue
            try:
                data = peer.recv(4096)
            except socket.timeout:
                continue
            if not data:
                return
            for binary, record in decoder.feed(data):
                pong_enabled, pong, ack_enabled, ack, request = self.options()
                if binary:
                    current, event = event, None
                    try:
                        timestamp, fields = reading(record)
                    except ValueError as error:
                        self.log("ERROR", f"Trama descartada: {error}")
                        continue
                    device, battery, height, photo, pallet, location = fields
                    detail = f"{device} · Palé {pallet} · Ubicación {location}"
                    self.log("RX", f"V2 {timestamp} · {detail} · Batería {battery} · Altura {height} · Foto {photo}")
                    if current is not None:
                        try:
                            result = save_event(db, device, current, record)
                        except sqlite3.Error as error:
                            self.log("ERROR", f"EVT={current} no guardado: {error}")
                            continue
                        self.log("EVT", f"{device} / {current}: {result}")
                        if ack_enabled and result != "conflicto":
                            self.send(peer, ack.replace("{id}", str(current)))
                        elif result == "conflicto":
                            self.log("ERROR", "El mismo ID ya tiene otra trama guardada. ACK omitido.")
                        else:
                            self.log("PAUSA", f"ACK omitido para {current}")
                    continue

                line = record.decode("ascii", errors="backslashreplace")
                self.log("RX", repr(line)[1:-1] if any(ord(c) < 32 for c in line) else line)
                if line == request:
                    if pong_enabled:
                        self.send(peer, pong)
                    else:
                        self.log("PAUSA", f"{request!r} recibido sin respuesta automática.")
                elif line.startswith("EVT="):
                    match = re.fullmatch(r"EVT=([0-9]{1,10})", line)
                    event = int(match[1]) if match and 0 < int(match[1]) <= 0xFFFFFFFF else None
                else:
                    event = None


class App:
    def __init__(self, root):
        self.root = root
        self.server = None
        self.worker = None
        self.messages = queue.Queue(maxsize=1000)
        self.responses = (True, "PONG", False, "ACK={id}", "PING")
        local_data = Path(os.environ.get("LOCALAPPDATA") or Path.home()) / "IDAUT" / "ServidorTCP"
        self.database = local_data / "eventos.sqlite3"

        root.title("IDAUT · Servidor TCP")
        root.geometry("1060x620")
        root.minsize(870, 510)
        root.configure(background="#101016")
        self.configure_style(root)

        body = ttk.Frame(root, style="Page.TFrame", padding=12)
        body.pack(fill="both", expand=True)
        body.columnconfigure(1, weight=1)
        body.rowconfigure(1, weight=1)
        ttk.Label(body, text="Servidor TCP de pruebas", style="PageTitle.TLabel").grid(
            row=0, column=0, columnspan=2, sticky="w", pady=(0, 10)
        )

        controls = ttk.Frame(body, style="Page.TFrame", width=375)
        controls.grid(row=1, column=0, sticky="new", padx=(0, 10))
        controls.columnconfigure(0, weight=1)
        bind = self.card(controls, "CONEXIÓN TCP", 0)
        bind.columnconfigure((0, 1), weight=1)
        self.host = tk.StringVar(value="0.0.0.0")
        self.port = tk.StringVar(value="51236")
        self.field(bind, "IP de escucha", self.host, 1, 0)
        self.field(bind, "Puerto", self.port, 1, 1, 10)
        self.connection_status = tk.StringVar(value="Servidor detenido")
        ttk.Label(bind, textvariable=self.connection_status, style="Muted.TLabel").grid(
            row=3, column=0, columnspan=2, sticky="w", pady=(7, 2)
        )
        ttk.Label(bind, text=f"IPv4 del PC · {local_addresses()}", style="Muted.TLabel", wraplength=325).grid(
            row=4, column=0, columnspan=2, sticky="w", pady=(0, 8)
        )
        self.start_button = ttk.Button(bind, text="Iniciar", command=self.start, style="Primary.TButton")
        self.start_button.grid(row=5, column=0, sticky="ew", padx=(0, 3))
        self.stop_button = ttk.Button(bind, text="Detener", command=self.stop, state="disabled")
        self.stop_button.grid(row=5, column=1, sticky="ew", padx=(3, 0))

        options = self.card(controls, "RESPUESTAS AUTOMÁTICAS", 1)
        options.columnconfigure((0, 1), weight=1)
        self.pong_enabled = tk.BooleanVar(value=True)
        self.ack_enabled = tk.BooleanVar(value=False)
        self.request = tk.StringVar(value="PING")
        self.pong = tk.StringVar(value="PONG")
        self.ack = tk.StringVar(value="ACK={id}")
        ttk.Checkbutton(options, text="Responder al heartbeat", variable=self.pong_enabled,
                        command=self.toggle_responses).grid(row=1, column=0, columnspan=2, sticky="w")
        self.field(options, "Mensaje recibido", self.request, 2, 0)
        self.field(options, "Respuesta", self.pong, 2, 1)
        ttk.Checkbutton(options, text="Confirmar eventos", variable=self.ack_enabled,
                        command=self.toggle_responses).grid(row=4, column=0, columnspan=2, sticky="w", pady=(6, 0))
        self.field(options, "Plantilla ACK", self.ack, 5, 0, span=2)
        ttk.Label(options, text="{id} se sustituye por el ID del evento.", style="Muted.TLabel").grid(
            row=7, column=0, columnspan=2, sticky="w", pady=(4, 6)
        )
        ttk.Button(options, text="Aplicar respuestas", command=self.apply_responses,
                   style="Save.TButton").grid(row=8, column=0, columnspan=2, sticky="ew")

        monitor = self.card(body, "MONITOR RX / TX", 1, column=1, sticky="nsew")
        monitor.columnconfigure(0, weight=1)
        monitor.rowconfigure(1, weight=1)
        ttk.Button(monitor, text="Limpiar", command=self.clear).grid(row=0, column=1, sticky="e", pady=(0, 6))
        output = ttk.Frame(monitor)
        output.grid(row=1, column=0, columnspan=2, sticky="nsew", pady=(2, 8))
        output.columnconfigure(0, weight=1)
        output.rowconfigure(0, weight=1)
        self.log = tk.Text(output, wrap="word", state="disabled", font=("Consolas", 10),
                           background="#FFFBFF", foreground="#25212A", padx=8, pady=8,
                           relief="flat", highlightthickness=1, highlightbackground="#D7D0DC")
        self.log.grid(row=0, column=0, sticky="nsew")
        for category, colour in {"RX": "#176439", "TX": "#155EAB", "ERROR": "#AB272D",
                                 "PAUSA": "#805000", "TCP": "#58476F"}.items():
            self.log.tag_configure(category, foreground=colour)
        scroll = ttk.Scrollbar(output, command=self.log.yview)
        scroll.grid(row=0, column=1, sticky="ns")
        self.log.configure(yscrollcommand=scroll.set)

        self.manual = tk.StringVar()
        ttk.Label(monitor, text="Envío manual").grid(row=2, column=0, sticky="w")
        entry = ttk.Entry(monitor, textvariable=self.manual)
        entry.grid(row=3, column=0, sticky="ew", padx=(0, 6))
        entry.bind("<Return>", lambda _: self.send_manual())
        ttk.Button(monitor, text="Enviar", command=self.send_manual, style="Primary.TButton").grid(
            row=3, column=1, sticky="ew"
        )
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(100, self.drain)

    @staticmethod
    def configure_style(root):
        style = ttk.Style(root)
        style.theme_use("clam")
        card = "#F5EFF9"
        ink = "#25212A"
        style.configure(".", font=("Segoe UI", 10), background=card, foreground=ink)
        style.configure("Page.TFrame", background="#101016")
        style.configure("PageTitle.TLabel", background="#101016", foreground="#F7F2FA", font=("Segoe UI", 19, "bold"))
        style.configure("Card.TFrame", background=card, borderwidth=1, relief="solid", bordercolor="#D7D0DC")
        style.configure("CardTitle.TLabel", font=("Segoe UI", 10, "bold"))
        style.configure("Muted.TLabel", foreground="#595060", font=("Segoe UI", 9))
        style.configure("TEntry", fieldbackground="#FFFBFF", padding=5, bordercolor="#B9B0BF")
        style.configure("TCheckbutton", padding=2)
        style.configure("TButton", background="#E7E0ED", foreground=ink, padding=(10, 6))
        for name, normal, active in (("Primary", "#196BBE", "#155A9F"), ("Save", "#2E7D32", "#256729")):
            key = name + ".TButton"
            style.configure(key, background=normal, foreground="white", bordercolor=normal)
            style.map(key, background=[("disabled", "#E1DAE6"), ("active", active)],
                      foreground=[("disabled", "#645D69"), ("!disabled", "white")])

    @staticmethod
    def card(parent, title, row, column=0, sticky="ew"):
        panel = ttk.Frame(parent, style="Card.TFrame", padding=10)
        panel.grid(row=row, column=column, sticky=sticky, pady=(0, 8) if column == 0 else 0)
        ttk.Label(panel, text=title, style="CardTitle.TLabel").grid(row=0, column=0, sticky="w", pady=(0, 5))
        return panel

    @staticmethod
    def field(parent, title, value, row, column, width=18, span=1):
        ttk.Label(parent, text=title).grid(row=row, column=column, columnspan=span, sticky="w", padx=(0, 6))
        ttk.Entry(parent, textvariable=value, width=width).grid(
            row=row + 1, column=column, columnspan=span, sticky="ew", padx=(0, 6)
        )

    def toggle_responses(self):
        _, pong, _, ack, request = self.responses
        self.responses = self.pong_enabled.get(), pong, self.ack_enabled.get(), ack, request
        self.report("CFG", f"Heartbeat {'activo' if self.responses[0] else 'en pausa'} · ACK {'activo' if self.responses[2] else 'en pausa'}")

    def apply_responses(self):
        try:
            request = validate_line(self.request.get())
            pong = validate_line(self.pong.get())
            if len(request) > 16 or len(pong) > 16:
                raise ValueError("El heartbeat admite hasta 16 caracteres por mensaje.")
            if request.startswith("EVT="):
                raise ValueError("EVT= está reservado para identificar lecturas.")
            ack = self.ack.get()
            if ack.count("{id}") != 1:
                raise ValueError("La plantilla ACK debe contener {id} una vez.")
            validate_line(ack.replace("{id}", "4294967295"))
        except ValueError as error:
            messagebox.showerror("Respuesta no válida", str(error))
            return
        self.responses = self.pong_enabled.get(), pong, self.ack_enabled.get(), ack, request
        self.report("CFG", f"Heartbeat {request!r} → {pong!r} · ACK {ack!r}")

    def start(self):
        try:
            host = str(ipaddress.IPv4Address(self.host.get().strip()))
            port = int(self.port.get())
            if not 1 <= port <= 65535:
                raise ValueError("Introduce un puerto entre 1 y 65535.")
            self.database.parent.mkdir(parents=True, exist_ok=True)
            self.server = Server(host, port, self.database, self.report, lambda: self.responses)
        except (OSError, ValueError) as error:
            messagebox.showerror("No se pudo iniciar", str(error))
            return
        self.worker = threading.Thread(target=self.server.run, daemon=True)
        self.start_button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.report("TCP", f"Escuchando en {host}:{port}")
        self.worker.start()

    def stop(self):
        if self.server:
            self.server.stop()
            self.stop_button.configure(state="disabled")

    def send_manual(self):
        try:
            line = validate_line(self.manual.get())
            if not self.server:
                raise ValueError("Inicia el servidor primero.")
            self.server.send_manual(line)
        except ValueError as error:
            messagebox.showerror("No se pudo enviar", str(error))
            return
        self.manual.set("")

    def report(self, category, message):
        try:
            self.messages.put_nowait((datetime.datetime.now().strftime("%H:%M:%S"), category, message))
        except queue.Full:
            pass

    def drain(self):
        lines = []
        for _ in range(100):
            try:
                at, category, message = self.messages.get_nowait()
            except queue.Empty:
                break
            lines.append((category, f"{at}  {category:<6} {message}\n"))
        if lines:
            self.log.configure(state="normal")
            for category, line in lines:
                self.log.insert("end", line, (category,))
            excess = int(self.log.index("end-1c").split(".")[0]) - 601
            if excess > 0:
                self.log.delete("1.0", f"{excess + 1}.0")
            self.log.see("end")
            self.log.configure(state="disabled")
        if self.worker and not self.worker.is_alive():
            self.worker = self.server = None
            self.start_button.configure(state="normal")
            self.stop_button.configure(state="disabled")
        if self.server and not self.server.stopped.is_set():
            self.connection_status.set("PLC conectado" if self.server.peer else "Escuchando · esperando PLC")
        else:
            self.connection_status.set("Servidor detenido")
        self.root.after(100, self.drain)

    def clear(self):
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.configure(state="disabled")

    def close(self):
        self.stop()
        if self.worker:
            self.worker.join(timeout=1)
        self.root.destroy()


def local_addresses():
    addresses = set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = item[4][0]
            if not address.startswith("127."):
                addresses.add(address)
    except OSError:
        pass
    return ", ".join(sorted(addresses)) or "consulta ipconfig"


if __name__ == "__main__":
    window = tk.Tk()
    App(window)
    window.mainloop()
