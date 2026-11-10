# IDAUT Lectura para Windows

Utilidad de prueba para solicitar una lectura desde el PC que ya recibe datos como teclado HID. La configuración del PLC se consulta por Bluetooth. El atajo se modifica en la app de mantenimiento, en **HID OUTPUT → Read shortcut (Windows)**, y se guarda con **Save format**.

1. Asigna el PC como receptor HID y comprueba **Send test text**.
2. Configura `CTRL+ALT+L` desde la app de mantenimiento.
3. Abre **IDAUT_Lectura.exe**, elige el PLC en **Emparejados** y pulsa **Conectar**.
4. Comprueba el atajo activo. Deja el foco en el formulario y pulsa el atajo.
5. Cambia el atajo desde mantenimiento con el programa conectado y comprueba que se actualiza. Deja el campo vacío para desactivarlo.

La utilidad se puede ocultar en la bandeja de Windows. Usa **Salir** desde su icono para cerrarla. Si Windows rechaza la combinación, la utilidad conserva el atajo anterior y muestra un aviso.

## Ejecutar o empaquetar en Windows

Desde esta carpeta con Python 3.12:

```powershell
py -3.12 -m pip install -r requirements.txt
py -3.12 idaut_lectura.py
py -3.12 -m pip install pyinstaller
py -3.12 -m PyInstaller --noconfirm --onefile --windowed --name IDAUT_Lectura --collect-submodules bleak.backends.winrt --collect-submodules pystray idaut_lectura.py
```

El ejecutable queda en `dist/IDAUT_Lectura.exe`.
