# IDAUTBLE — app Android y firmware PLC Scanner

Código coordinado de la app de mantenimiento y del PLC ESP32-S3.

| Componente | Carpeta | Versión |
|---|---|---|
| App Android | [android/](android/) | 1.3.10-plc3, versionCode 14 |
| Firmware PLC Scanner | [firmware/](firmware/) | 3.0.6-rc1 |

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
./gradlew testDebugUnitTest assembleDebug lintDebug
```

En Windows, usa `gradlew.bat testDebugUnitTest assembleDebug lintDebug` dentro de android.

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

C verifica que el PLC responde. La app consulta INFO? y STATUS? una sola vez por conexión; toma el ID de STATUS y evita el ID? automático. ID? queda disponible al abrir Configure ID, donde se consulta también HWID y el nombre Bluetooth real. Su respuesta contiene FW (versión), PROTO (trama V2), ACKMODE (confirmación de eventos) y HEARTBEAT (vigilancia TCP).

## ID de la carretilla

Con firmware 3.0.5 y app 1.3.6, conecta y abre la pestaña TERMINAL. Debajo de
BINARY COMMANDS, despliega **PIN / ACCESS** y pulsa **Enter PIN / Authorise**.
Después despliega **DEVICE ID** y pulsa **Configure ID**. Sin acceso
autorizado, ese botón muestra únicamente un aviso para introducir antes la
contraseña en PIN / ACCESS. Tras confirmar el PLC el nuevo ID, la ventana se cierra. Admite 1–20 caracteres ASCII (A-Z, 0-9, espacios,
guion y guion bajo), por ejemplo `E 1234 BCD`. Se normaliza a mayúsculas y
se guarda en el PLC. El nombre Bluetooth pasa a ser exactamente ese ID y las
tramas nuevas utilizan el mismo valor. No se reinicia el PLC ni cambia el PIN.
Desconecta y vuelve a buscar para ver el nuevo nombre anunciado.

Todas las secciones del panel de comandos de TERMINAL se pliegan de forma
independiente, incluida BINARY COMMANDS. Conexión Bluetooth, BINARY COMMANDS
y READINGS empiezan desplegados; el resto empieza plegado. READINGS aparece
justo después de BINARY COMMANDS, seguido de PIN / ACCESS y DEVICE ID.
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

- Android: compilación y generación de APK debug correctas; 47 pruebas unitarias superadas.
- Firmware: compilación correcta con ESP32 3.0.7; 97 comprobaciones de lógica, 5 comprobaciones de validación del PIN y 48.041 comprobaciones del LED y 65 comprobaciones de identidad y 30 de diagnóstico STATUS superadas.
- Las pruebas de emparejamiento, electrónica, relé, baterías y OTA en el equipo real siguen pendientes.

Informes: [Android](android/VALIDACION_ANDROID.md) y [firmware](firmware/VALIDACION.md).


## Idioma de la app (1.3.5)

Todos los textos propios de la app se presentan en inglés: menú, diálogos, avisos,
errores, registro de diagnóstico y accesibilidad. Los comandos, UUID y respuestas
recibidas del PLC se conservan literalmente; por ejemplo, CONECTADO sigue siendo
el token del protocolo. Los diálogos del sistema Android mantienen el idioma del
terminal. No requiere actualizar firmware.


## PIN e ID: acciones y permisos (1.3.6)

Los botones de mantenimiento usan la misma presentación compacta que el resto:
verde para autorizar/guardar, naranja para bloquear, rojo para olvidar el PIN
local y azul para configurar el ID. El estado del acceso se muestra en una
etiqueta verde (autorizado) o gris (resto de estados), con su texto explicativo.

Las llamadas de cierre GATT y confirmación de escritura larga comprueban el permiso
Bluetooth y tratan SecurityException explícitamente. Si se pierde el permiso
durante un envío, se cancela el trabajo pendiente y se revoca la sesión local.
No cambia el firmware ni la vinculación/PIN del PLC.

Prueba adicional de los helpers reales (Python 3, JDK 17 y caché de Gradle tras
compilar): `python3 android/tests/test_gatt_permissions.py`. Se pueden indicar
JAVA_HOME y GRADLE_USER_HOME iguales a los usados para Gradle. Esta prueba no
sustituye la comprobación en Android al revocar permisos desde Ajustes.

## Device status (app 1.3.9 / firmware 3.0.6)

En TERMINAL, después de DEVICE ID, **DEVICE STATUS** es una sección plegable.
Se consulta una vez al completar el handshake BLE y con **Refresh**;
no requiere autorizar mantenimiento. Muestra firmware e ID, modo/motivo del LED,
ambas baterías (tensión, porcentaje estimado, ausencia o medida inválida/antigua),
la batería presente de menor nivel, WiFi habilitado, enlace TCP, notificaciones BLE,
fotocélula estabilizada, RTC, expansor de salidas y corte solicitado/confirmado.

Es una instantánea: muestra su antigüedad y avisa pasados 60 s. La edad de cada
muestra ADC se refiere al momento de la consulta. Al desconectar se borra todo
el estado para no mostrar datos de otra carretilla. Tras 6 s sin respuesta se
avisa de conexión o firmware incompatible; los demás comandos siguen disponibles.
Las respuestas incompletas, malformadas o de esquema desconocido no sustituyen
la última instantánea válida. No hay sondeo continuo ni ACK automático de eventos.

Actualizar **ambos componentes** para esta función. El comando nuevo `STATUS?`
es de solo lectura y devuelve `<ACK>STATUS=1;...\r\n`; no modifica V2, DIAG?,
los patrones del LED, la política de corte ni la configuración persistida.
Detalles del esquema: [firmware/LEEME.md](firmware/LEEME.md).
Prueba adicional: `python3 firmware/tests/test_status.py`.


## Panel visual de estado (app 1.3.9)

DEVICE STATUS conserva la consulta y sus datos, presentados en tarjetas:
círculo animado para el patrón del LED, barras y porcentajes de ambas baterías,
e indicadores circulares para WiFi, TCP, BLE seguro, fotocélula, RTC, expansor
y corte. Se mantienen tensión, antigüedad de la muestra, batería de menor nivel,
firmware, ID y motivo del LED/corte. Las barras distinguen batería ausente o
medida inválida/antigua de una batería presente al 0 %.

La animación reproduce la cadencia de firmware 3.0.6, con fase ilustrativa:
STATUS no transmite sincronización del LED físico. Los colores son indicadores
de la app; el PLC conserva su único LED. Al plegar se pausa el parpadeo; sin
instantánea reciente se detiene y todos los indicadores pasan a gris. Se avisa
al superar 60 s desde la recepción. Las barras conservan el último nivel visible
con la indicación Last snapshot. Refresh consulta de nuevo como antes;
no se añade sondeo ni se modifican los comandos.

Incluye colores adaptados a temas claro/oscuro y disposición vertical de baterías
en paneles estrechos o con fuente grande. Solo requiere actualizar la app;
el firmware 3.0.6 de la función STATUS permanece igual.


### Presentación compacta (1.3.9)

El círculo LED pasa de 54 a 22 dp; los porcentajes de 26 a 16 sp y comparten
línea con el nombre de la batería. Se reducen rellenos, separaciones y la altura de línea del panel (base 13 sp). Las seis
tarjetas de conexiones se sustituyen por filas de indicadores dentro de un solo
panel (dos columnas si hay espacio). ID, firmware, antigüedad y Refresh comparten
cabecera; se omite el mensaje redundante de recepción correcta, conservando errores.

La edad de las muestras ADC y las explicaciones del patrón/umbrales siguen
accesibles en **Diagnostic details**, plegado de inicio. El estado del LED, su
motivo, barras/voltios, conexiones y corte permanecen visibles. La instantánea
conserva color y vista previa durante 60 s; luego sigue visible en gris con aviso.
Solo se actualiza la app; firmware y frecuencia de consultas permanecen iguales.


## Lecturas visuales y consulta inicial (app 1.3.10)

En TERMINAL, **READINGS** aparece inmediatamente debajo de BINARY COMMANDS,
inicialmente desplegado y plegable. Muestra los códigos de palet/ubicación con
texto seleccionable, fotocélula 0/1, altura en metros y porcentajes/barritas de
ambas baterías. Incluye ID de la trama, hora PLC y antigüedad local de recepción.
Se actualiza con cada trama V2 completa de lectura o evento automático; el registro
ASCII/HEX permanece disponible. Esta vista no envía ACK de eventos de negocio.

Cada respuesta sustituye todos los campos: `-`/vacío se presenta sin dato,
sin arrastrar códigos de otra lectura. Los valores B1/B2 son los porcentajes
recibidos; la trama V2 no permite distinguir un 0 % de un slot ausente, por lo
que la presencia se consulta en Device status. Una trama malformada conserva la
última lectura válida con aviso, y al desconectar se borra la vista.
Un RTC inválido no impide mostrar los códigos; se indica que el reloj no está disponible.

La conexión solicita solo INFO? y STATUS? una vez. El ID de STATUS actualiza la
cabecera de DEVICE ID. Configure ID mantiene su consulta explícita ID? para
obtener HWID/nombre Bluetooth, sin inventar esos datos a partir de STATUS.
El panel compacto y sus 60 s de frescura se conservan. Solo hay que actualizar la app.
