# IDAUTBLE — app Android y firmware PLC Scanner

Código coordinado de la app de mantenimiento y del PLC ESP32-S3.

| Componente | Carpeta | Versión |
|---|---|---|
| App Android | [android/](android/) | 1.3.5-plc3, versionCode 9 |
| Firmware PLC Scanner | [firmware/](firmware/) | 3.0.5-rc1 |

## Trabajar sin ZIP

Clona el repositorio una vez:

```bash
git clone https://github.com/Yyimy/IDAUTBLE.git
cd IDAUTBLE
```

Para obtener los cambios que ya estén integrados en main, con tu copia local sin cambios pendientes:

```bash
git switch main
git pull --ff-only
```

Los cambios posteriores se preparan en ramas y pull requests. Guarda tus propios cambios en un commit o stash antes de actualizar si Git avisa de un conflicto.

## Android Studio

Abre la carpeta **android/**, no la raíz del repositorio. Sincroniza Gradle y ejecuta en el terminal Android.

Requisitos: JDK 17, Android SDK 36, Gradle 8.13, Android Gradle Plugin 8.13.2 y Kotlin 2.0.21. Las versiones están declaradas en el proyecto.

Pruebas y compilación desde la raíz:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

En Windows, usa `gradlew.bat testDebugUnitTest assembleDebug` dentro de android.

Instrucciones de conexión, red y OTA: [android/LEEME_PLC3.md](android/LEEME_PLC3.md).

## Arduino IDE

Abre **firmware/PLC_Scanner_v3/PLC_Scanner_v3.ino**; `Types.h` debe estar en la misma carpeta.

Usa **esp32 by Espressif Systems 3.0.7**, Adafruit ADS1X15 2.5.0 y Adafruit BusIO 1.16.2. Objetivo de compilación: ESP32S3 Dev Module, USB CDC habilitado y partición Minimal SPIFFS con OTA. Ajusta flash/PSRAM a la placa física.

Con Arduino CLI, desde la raíz:

```bash
arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc,USBMode=hwcdc,PartitionScheme=min_spiffs firmware/PLC_Scanner_v3 --warnings all
python3 firmware/tests/test_logic.py
python3 firmware/tests/test_pin_constant.py
python3 firmware/tests/test_led.py
python3 firmware/tests/test_identity.py
```

Estados del indicador: [LED del PLC](firmware/LEEME.md#led-de-estado-version-303).

Instrucciones y hardware: [firmware/LEEME.md](firmware/LEEME.md).

## PIN, autorización e INFO

El firmware incluye **123456 como PIN de ejemplo**, configurable en `PLC_MAINTENANCE_PIN`. No publiques tu PIN real ni contraseñas WiFi en commits. Este repositorio es público.

Tras emparejar, la app permite consultar datos. PIN / Acceso autoriza el mantenimiento durante la conexión completa, sin límite temporal. Bloquear acceso envía AUTH=OFF; desconectar o reiniciar también revoca. Recordar PIN es opcional y usa Android Keystore.

La contraseña WiFi del AP OTA es independiente del PIN BLE; el firmware la muestra localmente por USB con el comando `info`.

C verifica que el PLC responde. La app consulta INFO? una sola vez por conexión. Su respuesta contiene FW (versión), PROTO (trama V2), ACKMODE (confirmación de eventos) y HEARTBEAT (vigilancia TCP).

## ID de la carretilla

Con firmware 3.0.5 y app 1.3.5, conecta y abre la pestaña TERMINAL. Debajo de
BINARY COMMANDS, despliega **PIN / ACCESS** y pulsa **Enter PIN / Authorise**.
Después despliega **FORKLIFT ID** y pulsa **Configure ID**. Sin acceso
autorizado, ese botón muestra únicamente un aviso para introducir antes la
contraseña en PIN / ACCESS. Tras confirmar el PLC el nuevo ID, la ventana se cierra. Admite 1–20 caracteres ASCII (A-Z, 0-9, espacios,
guion y guion bajo), por ejemplo `E 1234 BCD`. Se normaliza a mayúsculas y
se guarda en el PLC. El nombre Bluetooth pasa a ser exactamente ese ID y las
tramas nuevas utilizan el mismo valor. No se reinicia el PLC ni cambia el PIN.
Desconecta y vuelve a buscar para ver el nuevo nombre anunciado.

Todas las secciones del panel de comandos de TERMINAL se pliegan de forma
independiente, incluida BINARY COMMANDS. Conexión Bluetooth y comandos binarios
empiezan desplegados; el resto empieza plegado. PIN / ACCESS aparece justo
después de BINARY COMMANDS y antes de FORKLIFT ID.
Las cabeceras de ID y acceso conservan el ID
y el estado de acceso visibles. La elección se mantiene al cambiar de pestaña
y durante recreaciones de pantalla compatibles con el estado guardado de Android.

El cambio admite un palet cargado: no exige fotocélula a 0. Se requiere ausencia
de lecturas, eventos pendientes u OTA y fotocélula inicial estabilizada. El identificador técnico de fábrica sigue disponible como HWID y el SSID
OTA sigue basándose en él. Si no se configura un ID propio, se conserva el ID
original y el nombre `ISP-<HWID>`.

**Integración:** el campo ID de V2 deja de estar limitado a 12 HEX y puede tener
1–20 caracteres; leerlo hasta ESC, no por posición fija. Revisar los receptores
externos antes de desplegar IDs propios. Asignar un ID distinto a cada carretilla.
Detalles: [firmware](firmware/LEEME.md#id-configurable-de-la-carretilla-version-304)
y [app](android/LEEME_PLC3.md#id-de-la-carretilla).

## Validación de esta base

- Android: compilación y generación de APK debug correctas; 26 pruebas unitarias superadas.
- Firmware: compilación correcta con ESP32 3.0.7; 97 comprobaciones de lógica, 5 comprobaciones de validación del PIN y 48.041 comprobaciones del LED y 65 comprobaciones de identidad superadas.
- Las pruebas de emparejamiento, electrónica, relé, baterías y OTA en el equipo real siguen pendientes.

Informes: [Android](android/VALIDACION_ANDROID.md) y [firmware](firmware/VALIDACION.md).


## Idioma de la app (1.3.5)

Todos los textos propios de la app se presentan en inglés: menú, diálogos, avisos,
errores, registro de diagnóstico y accesibilidad. Los comandos, UUID y respuestas
recibidas del PLC se conservan literalmente; por ejemplo, CONECTADO sigue siendo
el token del protocolo. Los diálogos del sistema Android mantienen el idioma del
terminal. No requiere actualizar firmware.
