# IDAUTBLE — app Android y firmware PLC Scanner

Código coordinado de la app de mantenimiento y del PLC ESP32-S3.

| Componente | Carpeta | Versión |
|---|---|---|
| App Android | [android/](android/) | 1.2-plc3, versionCode 3 |
| Firmware PLC Scanner | [firmware/](firmware/) | 3.0.2-rc1 |

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
```

Instrucciones y hardware: [firmware/LEEME.md](firmware/LEEME.md).

## PIN, autorización e INFO

El firmware incluye **123456 como PIN de ejemplo**, configurable en `PLC_MAINTENANCE_PIN`. No publiques tu PIN real ni contraseñas WiFi en commits. Este repositorio es público.

Tras emparejar, la app permite consultar datos. PIN / Acceso autoriza el mantenimiento durante la conexión completa, sin límite temporal. Bloquear acceso envía AUTH=OFF; desconectar o reiniciar también revoca. Recordar PIN es opcional y usa Android Keystore.

La contraseña WiFi del AP OTA es independiente del PIN BLE; el firmware la muestra localmente por USB con el comando `info`.

C verifica que el PLC responde. La app consulta INFO? una sola vez por conexión. Su respuesta contiene FW (versión), PROTO (trama V2), ACKMODE (confirmación de eventos) y HEARTBEAT (vigilancia TCP).

## Validación de esta base

- Android: compilación y generación de APK debug correctas; 19 pruebas unitarias superadas.
- Firmware: compilación correcta con ESP32 3.0.7; 97 comprobaciones de lógica y 5 comprobaciones de validación del PIN superadas.
- Las pruebas de emparejamiento, electrónica, relé, baterías y OTA en el equipo real siguen pendientes.

Informes: [Android](android/VALIDACION_ANDROID.md) y [firmware](firmware/VALIDACION.md).
