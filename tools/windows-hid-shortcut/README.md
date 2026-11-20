# IDAUT Lectura para Windows

Utilidad de prueba para solicitar una lectura desde el PC que ya recibe datos como teclado HID. La configuración del PLC se consulta por Bluetooth. El atajo se modifica en la app de mantenimiento, en **HID OUTPUT → Read shortcut (Windows)**, y se guarda con **Save format**.

1. Asigna el PC como receptor HID y comprueba **Send test text**.
2. Configura `CTRL+ALT+L` desde la app de mantenimiento.
3. Abre **IDAUT_Lectura.exe**, elige el PLC en **Emparejados** y pulsa **Conectar**.
4. Comprueba el atajo activo. Deja el foco en el formulario y pulsa el atajo.
5. Cambia el atajo desde mantenimiento con el programa conectado y comprueba que se actualiza. Deja el campo vacío para desactivarlo.

Pulsa **Desconectar** para cerrar el canal de lectura y **Conectar** para volver a abrirlo. El programa conserva el último PLC conectado e intenta reconectar con él al arrancar. La **X** oculta la ventana en la bandeja de Windows. Usa **Salir** desde su icono para cerrar el programa por completo. Si Windows rechaza la combinación, la utilidad conserva el atajo anterior y muestra un aviso.

## Ejecutar o empaquetar en Windows

Desde esta carpeta con Python 3.12:

```powershell
py -3.12 -m pip install -r requirements.txt
py -3.12 idaut_lectura.py
py -3.12 -m pip install pyinstaller
py -3.12 -c "from idaut_lectura import barcode_icon; barcode_icon().save('shortcut.ico')"
py -3.12 -m PyInstaller --noconfirm --onefile --windowed --icon shortcut.ico --name IDAUT_Lectura --collect-submodules bleak.backends.winrt --collect-submodules pystray idaut_lectura.py
```

El ejecutable queda en `dist/IDAUT_Lectura.exe`.

### Modo maniobra

Con un PLC compatible, **Pausar lecturas** evita que la fotocélula dispare
nuevas lecturas automáticas. Cuando el PLC confirme **Lecturas pausadas**,
mueve el palet de delante. Pulsa **Reanudar lecturas** antes de recoger el
palet del fondo. El siguiente cambio de la fotocélula disparará la lectura.

**Ctrl + Alt + Mayús + L** activa o desactiva el modo. El botón sigue
disponible si el atajo está ocupado en Windows. El estado confirmado se
muestra en la ventana y la pausa también aparece en el icono de bandeja.
