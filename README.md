# IDAUTBLE — app Android y firmware PLC Scanner

Código coordinado de la app de mantenimiento y del PLC ESP32-S3.

| Componente | Carpeta | Versión |
|---|---|---|
| App Android | [android/](android/) | 1.3.32-plc3, versionCode 36 |
| Firmware PLC Scanner | [firmware/](firmware/) | 3.0.22-rc1 |

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

Abre **firmware/PLC_Scanner_v3/PLC_Scanner_v3.ino**; mantén todos sus archivos `.h` en la misma carpeta.

Usa **esp32 by Espressif Systems 3.0.7**, Adafruit ADS1X15 2.5.0 y Adafruit BusIO 1.16.2. Objetivo de compilación: ESP32S3 Dev Module, USB CDC habilitado y partición Minimal SPIFFS con OTA. Ajusta flash/PSRAM a la placa física.

Con Arduino CLI, desde la raíz:

```bash
arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc,USBMode=hwcdc,PartitionScheme=min_spiffs firmware/PLC_Scanner_v3 --warnings all
python3 firmware/tests/test_logic.py
python3 firmware/tests/test_pin_constant.py
python3 firmware/tests/test_led.py
python3 firmware/tests/test_identity.py
python3 firmware/tests/test_status.py
python3 firmware/tests/test_height.py
python3 firmware/tests/test_ota_config.py
```

Estados del indicador: [LED del PLC](firmware/LEEME.md#led-de-estado-version-303).

Instrucciones y hardware: [firmware/LEEME.md](firmware/LEEME.md).

## PIN, autorización e INFO

El firmware incluye **123456 como PIN de ejemplo**, configurable en `PLC_MAINTENANCE_PIN`. No publiques tu PIN real ni contraseñas WiFi en commits. Este repositorio es público.

Tras emparejar, la app permite consultar datos. PIN / Acceso autoriza el mantenimiento durante la conexión completa, sin límite temporal. Bloquear acceso envía AUTH=OFF; desconectar o reiniciar también revoca. Recordar PIN es opcional y usa Android Keystore.

La contraseña WiFi del AP OTA es independiente del PIN BLE; el firmware la muestra localmente por USB con el comando `info`.

C verifica que el PLC responde. La app consulta INFO? una sola vez por conexión y STATUS? al conectar, periódicamente y tras lecturas; toma el ID de STATUS y evita el ID? automático. ID? queda disponible al abrir Configure ID, donde se consulta también HWID y el nombre Bluetooth real. Su respuesta contiene FW (versión), PROTO (trama V2), ACKMODE (confirmación de eventos) y HEARTBEAT (vigilancia TCP).

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
OTA utiliza `PLC_OTA_<ID configurado>` a partir del firmware 3.0.8. Si no se configura un ID propio, se conserva el ID
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

En TERMINAL, entre READINGS y PIN / ACCESS, **DEVICE STATUS** es una sección plegable.
Se consulta al completar el handshake BLE, cada 30 s y tras una lectura, además de **Refresh**;
no requiere autorizar mantenimiento. Muestra firmware e ID, modo/motivo del LED,
ambas baterías (tensión, porcentaje estimado, ausencia o medida inválida/antigua),
la batería presente de menor nivel, WiFi habilitado, enlace TCP, notificaciones BLE,
fotocélula estabilizada, RTC, expansor de salidas y corte solicitado/confirmado.

Es una instantánea: muestra su antigüedad y avisa pasados 60 s. La edad de cada
muestra ADC se refiere al momento de la consulta. Al desconectar se borra todo
el estado para no mostrar datos de otra carretilla. Tras 6 s sin respuesta se
avisa de conexión o firmware incompatible; los demás comandos siguen disponibles.
Las respuestas incompletas, malformadas o de esquema desconocido no sustituyen
la última instantánea válida. El sondeo automático se limita al primer plano, con BLE listo y fuera de OTA; no hay ACK automático de eventos.

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


## Actualización automática de Device status (app 1.3.11)

Orden en TERMINAL: BINARY COMMANDS → READINGS → DEVICE STATUS → PIN / ACCESS → DEVICE ID.
Las secciones conservan su plegado. STATUS se actualiza también con el panel plegado.

La app consulta STATUS? al conectar y, si Auto-refresh está activado, cada 30 s
desde el último intento, solo en primer plano, con BLE preparado y fuera de OTA.
Desde 1.3.17, After reads también atiende tramas V2 válidas de la fotocélula o del
servidor, además de L/B/A/P/D/I solicitados en la app. Si hay una consulta reciente,
conserva un refresco pendiente hasta completar el intervalo de 5 s; no lo descarta.
STATUS no reemplaza los códigos de READINGS. Con firmware compatible se consulta
COUNTERS? después de STATUS para actualizar también la tabla.

Se evita duplicar solicitudes pendientes y se espera a que la cola BLE esté libre.
Una lectura sin respuesta deja de bloquear el sondeo a los 8 s; una consulta STATUS
sin respuesta termina a los 6 s y el siguiente intento periódico respeta los 30 s.
V2 no incluye identificador de transacción: si coincide un evento automático con
una lectura solicitada, la siguiente trama válida da por terminada esa espera.
Al volver del segundo plano se consulta si corresponde por antigüedad; no se
acumulan consultas durante la pausa. Desconectar reinicia toda la planificación.
OTA se mantiene pausada hasta reconectar, confirmar estado inactivo o recibir un
error terminal; BUSY/BAD_PARAMS no implican fin de OTA. Reset UI no elimina esa pausa.

Refresh permanece disponible y los datos pasan a antiguos después de 60 s sin
respuesta válida. Se conserva el firmware 3.0.6-rc1 y el protocolo V2.


## After reads (app 1.3.12)

DEVICE STATUS incorpora un interruptor compacto **After reads**, junto a Refresh
en la misma cabecera, sin una fila de explicación adicional. Empieza activado;
la preferencia se guarda en la app y se conserva al cerrar, reconectar o cambiar
de PLC. Se puede configurar también estando desconectado.

Desactivarlo elimina la consulta STATUS? adicional tras lecturas manuales o del sensor; mantiene
STATUS al conectar, cada 30 s y con Refresh. La consulta periódica puede coincidir
con una lectura aunque After reads esté apagado. La app sigue esperando el fin
de la lectura antes de enviar una consulta periódica, para no intercalarlas.

Al apagar se descarta la actualización tras lectura todavía no enviada; una
petición STATUS ya enviada puede completar su respuesta. Activar el interruptor
no envía comandos: afecta a las siguientes respuestas de lectura. No modifica
READINGS, los tiempos de caducidad, la pausa OTA ni el firmware 3.0.6-rc1.


## Estilo compacto uniforme (app 1.3.12)

Las secciones utilizan la misma base tipográfica que READINGS y DEVICE STATUS:
texto y botones de 11 sp / línea 13 sp, títulos de sección de 12 sp / línea 15 sp
y valores técnicos editables de 12 sp / línea 14 sp en monoespaciado. También
se aplica a NETWORK y OTA a través del tema común. Los títulos de diálogos
conservan una jerarquía algo mayor y los porcentajes de STATUS mantienen su
formato propio.

Cabeceras con resumen compacto, tarjetas de radio 8 dp y botones/campos de radio
6 dp sustituyen la apariencia de cápsula. BARCODE ID / RTC conserva sus acciones
y campos, con etiquetas alineadas de 52 dp para evitar comprimir Pallet/Spot/RTC.
Se mantienen colores funcionales, plegado, edición y comandos. El tamaño de
fuente sigue escalando con los ajustes de Android.


## Altura RS485 y temporizador de A (firmware 3.0.7-rc1)

El receptor S85 valida el orden de los tres bytes (01/10/11), usa los 4 bits de
datos del primero y 6 de los siguientes y recupera sincronización tras ruido o
respuestas parciales. Los bits xx no documentados del primer byte se rechazan
conservadoramente (antes tampoco podían producir una distancia dentro de rango).
Solo acepta 200…20000 mm y mantiene el formato V2 MM.CC en metros. El plazo
de 250 ms se comprueba antes de leer/aceptar bytes y el trabajo RX se limita a
64 bytes por vuelta. Las respuestas inválidas, incompletas o fuera de plazo no
reutilizan alturas anteriores.

Cada A explícita produce por USB una línea HEIGHT A con el tiempo desde la
recepción del comando en el PLC hasta entregar la respuesta completa al
transporte de origen. Incluye las colas de comandos, escáner y transmisión, y
desglosa sensor_ms y total_ms. No incluye el tiempo previo desde el clic en la
app ni confirma recepción en la tablet. No añade mensajes BLE/TCP ni campos V2.
La app actual no necesita actualización.

Instrucciones y significado de tiempos/resultados: [firmware/LEEME.md](firmware/LEEME.md#altura-y-temporizador-del-comando-a-firmware-307-rc1).


## Auto-refresh y conexión centrada (app 1.3.13)

DEVICE STATUS añade **Auto-refresh**, activado por defecto, para habilitar o
deshabilitar únicamente las consultas periódicas STATUS? de cada 30 s. La
preferencia se guarda en la app, se conserva al cerrar/reabrir y reconectar,
y se puede ajustar desconectado. **After reads** conserva su propia preferencia.

| Auto-refresh | After reads | Consultas posteriores a la conexión |
|---|---|---|
| ON | ON | Cada 30 s y después de lecturas, con las protecciones contra duplicados. |
| ON | OFF | Solo cada 30 s o con Refresh. |
| OFF | ON | Solo después de lecturas o con Refresh. |
| OFF | OFF | Solo con Refresh. |

La consulta inicial al conectar es independiente de ambos interruptores, incluso
si quedó aplazada hasta volver al primer plano. Una petición ya enviada puede
terminar tras apagar el interruptor. Al reactivarlo se consulta una sola vez
si ya han transcurrido 30 s desde el último intento; no se acumulan consultas
atrasadas. Se mantienen pausa OTA/segundo plano, espera de lecturas, cola BLE y
caducidad visual de 60 s.

Ambos interruptores mantienen el aspecto compacto de After reads y comparten
la cabecera con Refresh. En anchuras inferiores a 340 dp o fuente ampliada
se sitúan juntos en una fila debajo para evitar apretar los datos del equipo.
La fila Scan 5s / Show Devices / Connect / Disconnect de BLUETOOTH CONNECTION
queda centrada horizontalmente, conservando sus separaciones, colores y acciones.
Solo requiere actualizar la app; el firmware no cambia.

También se centran los botones Get Status / Enable-Disable de HEIGHT MEASUREMENT.
En BARCODE ID / RTC se centra el conjunto de botones/campos y se limita a 560 dp
cuando sobra anchura, manteniendo las columnas y la alineación de Pallet/Spot/RTC.
En espacios menores sigue adaptándose al ancho disponible.


## WiFi OTA: clave fija y nombre personalizado (firmware 3.0.8 / app 1.3.14)

La clave se edita al principio del INO, junto al PIN, en esta única línea:

```cpp
static constexpr char PLC_OTA_WIFI_PASSWORD[] = "12345678";
```

El repositorio entrega ese valor de ejemplo. Se permiten 8…63 bytes; usar letras,
números o símbolos ASCII y respetar las comillas de C++. Compilar y cargar para
aplicar la nueva clave. El valor compilado prevalece siempre sobre la antigua
clave aleatoria del equipo; no hace falta borrar NVS. No modifica el PIN ni
las vinculaciones Bluetooth. `info` por USB sigue mostrando la clave activa.

La red se anuncia como `PLC_OTA_<ID configurado>`: con ID 777, `PLC_OTA_777`;
con ID FORK 7, `PLC_OTA_FORK 7`. Se recalcula en cada OTA=START, por lo que un
cambio de ID se refleja en la siguiente sesión OTA sin reiniciar el equipo.
La red solo se anuncia mientras está activo el AP OTA.

La app conserva el SSID completo aunque el ID tenga espacios. Si Android tiene
guardada la red con la contraseña anterior, olvidar esa red o actualizar su
clave y conectarse de nuevo. No se cambia la trama V2 ni el transporte OTA.


## OTA al cambiar de pestaña (app 1.3.15)

La actualización continúa al pasar entre OTA, TERMINAL y NETWORK. Se conservan
archivo, SHA256, servidor y progreso; el porcentaje aparece en la pestaña OTA.
Tras reiniciar y reconectar, se muestra la versión del firmware en ejecución junto
a la anterior. El resultado permanece disponible al volver a OTA y se puede repetir
la prueba con otro BIN. Mantener la app abierta: no se garantiza continuidad si
Android destruye la Activity o finaliza el proceso. No requiere cambiar el firmware.


## DHCP en Arduino-ESP32 3.0.7 (firmware 3.0.9)

Corregida la activación DHCP: el core requiere local_ip 0.0.0.0; INADDR_NONE
configuraba 255.255.255.255 como dirección estática. El PLC podía asociarse al AP
(LINK=UP y RSSI válido) sin disponer de una IP utilizable ni conexión TCP.
Ahora solicita dirección al router y no inicia WiFi si falla la configuración IP.
No requiere actualizar la app ni cambiar la configuración guardada.
Tras cargarlo, comprobar NETSTAT?: IP válida de la red y, si el servidor escucha,
TCP=UP. NET? conserva los campos IP/GW/MASK del modo STATIC; en DHCP la dirección
real se consulta con NETSTAT?, no se presupone la IP estática guardada.


## TCP y separación de respuestas (firmware 3.0.10)

Al conectar TCP se envía una sola línea CONNECTED con RTC, ID e IP conservados
al principio, seguida de PROTO, FW, ACKMODE y HEARTBEAT; termina en CRLF.
Las consultas de configuración/diagnóstico responden solo por la conexión que
las pidió. El mantenimiento BLE no añade CONECTADO, INFO o STATUS al servidor.
NETSTAT de cambios de enlace se anuncia solo a BLE; TCP puede solicitar NETSTAT?.
Las lecturas binarias L/B/A/P/D/I y los eventos de palet conservan las salidas
BLE y TCP cuando están disponibles, también si la orden la envía el operario por BLE.
No cambia la trama V2 ni el modo de confirmación/persistencia de eventos.
Detalles y matriz de pruebas en firmware/LEEME.md y firmware/VALIDACION.md.


## Contadores y eventos en Device Status (app 1.3.16 / firmware 3.0.11)

DEVICE STATUS incluye una tabla plegable COUNTERS & EVENTS, inicialmente cerrada.
Muestra arranques, activaciones PON, palets cargados/descargados, lecturas fallidas,
próximo ID de evento, pendientes, ID pendiente más antiguo y modo ACK. Solo lectura;
no hay botón de borrado ni se confirman eventos desde la app.

La consulta COUNTERS? está disponible por BLE y TCP. Responde solo por el canal
solicitante. STATUS anuncia COUNTERS=1 como capacidad; la app la consulta después
de cada STATUS válido, con Refresh, Auto-refresh y After reads según sus opciones.
Con firmware anterior se mantiene el diagnóstico habitual y se muestra que los
contadores requieren firmware compatible. El número siguiente EVT no es el número
de eventos pendientes ni un contador que se reinicie en cada encendido.


## Refresco tras lecturas del sensor (app 1.3.17)

After reads se aplica a cualquier trama de lectura V2 válida recibida: comandos
manuales, resultados solicitados desde TCP y eventos automáticos de fotocélula.
Funciona aunque Auto-refresh esté apagado. Con After reads apagado, recibir esas
tramas no solicita diagnóstico adicional; Refresh y el sondeo periódico siguen
sus reglas. No se envían ACK de eventos ni se altera la numeración EVT.

Las tramas incompletas o inválidas y las líneas EVT/STATUS/COUNTERS no disparan
el refresco. Lecturas seguidas y reintentos se agrupan en un único trabajo pendiente.
Se respeta un mínimo de 5 s desde la última petición y respuesta STATUS válida;
al terminar la espera se solicita STATUS y después COUNTERS si está soportado.
Las comprobaciones del temporizador son cada segundo y la cola puede añadir espera.
Esto evita perder el último cambio por un STATUS reciente sin generar consultas
por cada trama ni posponer indefinidamente con tráfico continuo. Se mantienen
las pausas de OTA, transporte ocupado y segundo plano, y la limpieza al desconectar.

Solo cambia la app. El firmware 3.0.11-rc1 que ofrece COUNTERS? sigue siendo válido.

## NETWORK: configuración y conexión (app 1.3.18)

NETWORK lee automáticamente la configuración por BLE, incluso con WiFi desactivado,
y muestra por separado ajustes guardados, asociación WiFi, IP real y conexión TCP.
La contraseña permanece oculta; `***` conserva el valor existente al guardar.
Los cambios sin guardar se mantienen al cambiar de pestaña y se borran al desconectar.

El guardado requiere OK más una configuración coincidente; habilitar WiFi requiere
OK más el estado recibido del PLC. Ninguna de esas confirmaciones asegura conexión
con el servidor. El estado se refresca cada 5 s con la pestaña visible y la app en primer
plano, temporalmente cada 2 s tras cambios. Se pausa durante OTA.
Hay límites de espera y mensajes distintos para problemas de WiFi, DHCP/IP y TCP.
Las escrituras no se reintentan automáticamente, y una orden pendiente de PIN que
ha caducado no se envía más tarde. Detalle y pruebas de campo en
[la guía Android](android/LEEME_PLC3.md#red).

Prueba en teléfono/PLC: abrir NETWORK con WiFi OFF, editar y cambiar de pestaña,
volver sin perder el borrador, guardar con PIN y confirmar lectura devuelta, habilitar
WiFi y comprobar IP DHCP/TCP reales. Probar también contraseña incorrecta, servidor
parado, cancelar PIN y desconectar/cambiar de PLC. La compilación y pruebas JVM no
sustituyen esta validación física.

## NETWORK en dos columnas (app 1.3.19)

En horizontal y con al menos 700 dp disponibles, LIVE CONNECTION ocupa el 40 % y
PLC CONFIGURATION el 60 %, con tarjetas alineadas y desplazamiento independiente.
En pantallas estrechas o en vertical se apilan. Los estados de conexión incorporan
pequeños indicadores de color y los campos conservan el estilo compacto.

Los refrescos automáticos dejan estables los botones y su texto. Refresh manual
sigue mostrando su progreso; guardar, consultar o activar WiFi puede adelantarse a
una consulta automática pendiente. No cambia la frecuencia de refresco ni se repiten
escrituras. Pendiente comprobar la distribución y el teclado en el teléfono/tablet real.

## Estilo común de mantenimiento (app 1.3.20)

TERMINAL y OTA adoptan el aspecto de NETWORK: tarjetas con esquinas de 8 dp,
fondo surfaceContainerLow y borde outlineVariant sobre el fondo común de pantalla.
Las pestañas usan una barra segmentada con iconos, tipografía técnica y selección
resaltada. Se conservan el progreso OTA en su pestaña y los colores de acciones,
tráfico, batería y errores.

OTA agrupa archivo/servidor y progreso en tarjetas; los pasos de actualización
mantienen sus controles y condiciones. La distribución de TERMINAL, sus secciones
plegables y el funcionamiento de BLE, red y OTA se conservan.
Pendiente revisar el resultado visual en el dispositivo real.

## Destello READY (firmware 3.0.12 / app 1.3.21)

El LED preparado emite un destello de 100 ms cada 3 segundos (2900 ms apagado).
Se conservan el significado de READY y las prioridades de batería, errores y OTA.
Actualizar el firmware para cambiar el LED físico y la app para que su vista previa y
etiqueta coincidan. La app mantiene la luz fija al conectar a firmware anterior a 3.0.12.
La fase de la animación es ilustrativa; no está sincronizada con el LED físico.

## Prototipo de coexistencia HID y mantenimiento

La primera fase de teclado HID está en
[firmware/HID_Coexistence_Probe](firmware/HID_Coexistence_Probe/README.md).
Es un sketch **experimental de banco**, separado de PLC_Scanner_v3: dos conexiones
BLE con roles distintos, texto sintético solicitado explícitamente, mantenimiento
por FFE0/FFE1 con PIN y prueba opcional de WiFi/TCP. Incluye pruebas host y una
matriz de comprobación Windows/Android pendiente en dispositivos reales.

El firmware operativo y la app conservan sus versiones y funcionamiento. La
integración del selector APP/HID y de las lecturas reales se hará tras validar
la prueba de coexistencia. Este sketch de banco no controla la carretilla ni
supervisa sus baterías; revisar las instrucciones antes de cargarlo.

## Teclado físico durante mantenimiento (app 1.3.22)

La actividad contempla cambios de tipo/disponibilidad de teclado y navegación,
además de orientación/tamaño. Así, registrar o retirar un teclado HID no recrea
la actividad por esos cambios, evitando perder su registro en memoria y cerrar
su GATT desde `onDestroy`. Compose recibe la nueva configuración mediante su
ciclo habitual. Esto no garantiza supervivencia a cierre de la app o del proceso.

En el prototipo HID, `ERR=PROBE_ONLY` al consultar `STATUS?` es esperado y no
significa rechazo del emparejamiento. El estado detallado se consulta por USB
con `status`: `APP=1` y `uart=1` confirman el canal de mantenimiento; el receptor
requiere rol `HID`, no solo `UNASSIGNED` con suscripción report/boot. Abrir
`HIDPAIR=1` por USB con solo el receptor candidato disponible permite asignarlo.
El anuncio neutro 0.1.2 ha autenticado y enviado texto a un Zebra ET40/Android 14
en las pruebas del usuario. Sigue pendiente validar la corrección de ciclo de
vida y las dos conexiones con envío iniciado desde la app.

## Receptor HID persistente (prototipo 0.2.0 / app 1.3.23)

El prototipo guarda un único receptor en su espacio `hid_probe`. La app compatible
muestra **HID RECEIVER · PROTOTYPE**, debajo de PIN / ACCESS, con Refresh, Assign
receiver, Cancel assignment, Forget receiver y Send test text. Cambiar la
asignación y enviar teclas requieren autorización de mantenimiento por sesión.

La recuperación exige autenticación MITM, un vínculo Bluetooth presente, identidad
coincidente y suscripción HID. Usa la identidad distribuida durante bonding y la
IRK para resolver direcciones privadas; las claves no se muestran en diagnósticos.
Un receptor desconocido o una identidad no resoluble no se asignan automáticamente.
Olvidar la asignación no borra el emparejamiento de los terminales. Mantener anuncio
neutro (`PROBE_ADVERTISE_HID=0`), validado en las pruebas anteriores con ET40.

**0.2.0 sigue siendo exclusivamente una imagen de banco**, sin control de
carretilla ni OTA. Ver el procedimiento de reinicio, reconexión, privacidad y
olvido en [la guía del prototipo](firmware/HID_Coexistence_Probe/README.md).

## Recuperación de notificaciones HID (prototipo 0.2.1)

Guarda las suscripciones Report/Boot del receptor elegido y las recupera tras
una conexión autenticada al mismo vínculo. Corrige el caso de 0.2.0 en el que
Android reconectaba sin reescribir CCCD y el receptor permanecía `READY=0`.
Una escritura nueva (también desactivar) prevalece sobre la caché y no se
habilitan notificaciones de otros clientes. App 1.3.23 compatible, sin cambios.

El registro v1 de 0.2.0 no contiene esas suscripciones: se conserva la identidad,
pero debe obtenerse una suscripción nueva. Ver la [migración de una sola vez](firmware/HID_Coexistence_Probe/README.md).
Continúa siendo exclusivamente un prototipo de banco.

## Telemetría TCP del prototipo (0.2.2)

Corrige la condición availableForWrite del core 3.0.7 que impedía enviar estados
y resultados HID aunque sí llegase el saludo TCP. Los tres mensajes usan una
cola de diagnóstico volátil con escrituras parciales no bloqueantes; se limpia
al desconectar/reconfigurar y no sustituye el ACK/outbox de negocio del firmware
operativo. Conserva la asignación HID y la compatibilidad con app 1.3.23. Ver el
[procedimiento WiFi/TCP](firmware/HID_Coexistence_Probe/README.md) para repetir la
prueba de coexistencia sin olvidar los terminales.


## Formato de teclado HID (prototipo 0.3.0 / app 1.3.24)

TERMINAL añade HID OUTPUT debajo de HID RECEIVER: Pallet / Location / Both,
separador configurable con texto ASCII y tokens `{TAB}`, `{ENTER}`, `{SPACE}`,
tecla final Enter / Tab / None e idioma de teclado US / Spanish. El ajuste se
guarda en el prototipo con autorización y confirmación del PLC, y se recupera
tras reiniciar. Send test text aplica el formato guardado a datos sintéticos.
El firmware operativo sigue en 3.0.12-rc1; las lecturas reales se integrarán después
de validar el nuevo formato. [Opciones y secuencia de prueba](firmware/HID_Coexistence_Probe/README.md#formato-configurable-030--app-1324).


## Teclado HID en el firmware operativo (3.0.13-rc1)

En la app 1.3.25, abre **HID RECEIVER** y autoriza los cambios desde **PIN / ACCESS**.
Pulsa **Assign receiver** y conecta desde los ajustes Bluetooth del terminal que
recibirá los códigos. Tras asignarlo, configura en **HID OUTPUT** los campos,
el separador, la tecla final y el idioma del teclado. Desde la app 1.3.26 con
firmware 3.0.14-rc1, el separador admite también `{UP}`, `{DOWN}`, `{LEFT}`
y `{RIGHT}`. Puedes combinarlas con texto, Tab y Enter.

La app 1.3.29 con PLC 3.0.16-rc1 añade Escape, Backspace, Delete, Insert,
Home, End, Page Up, Page Down y F1–F12 al separador y al selector de tecla final.
La tecla final admite también las flechas y espacio. La asignación y el formato
del PLC operativo se guardan por separado de los del prototipo. Usa el nombre
Bluetooth del PLC operativo para vincularlo.

Una lectura completa (fotocélula o comando L) envía los campos seleccionados
al receptor asignado y continúa por las rutas V2 habituales. Los resultados
de Send test text son sintéticos y se muestran en la app de mantenimiento.
Si falta un campo seleccionado, se omite el tecleo de esa lectura. Se admiten
cuatro lecturas HID pendientes, además de la que se está escribiendo. El monitor
serie registra los descartes por falta de receptor o cola llena. Los trabajos
HID están ligados a la conexión del receptor y se descartan al perderla. Los
reintentos de eventos V2 no vuelven a generar tecleo.

Para la primera prueba física, carga por USB el sketch completo en una placa de
pruebas. Conecta la app y el receptor HID desde terminales distintos. Comprueba
Send test text, una lectura completa con TCP activo, una lectura con un campo
ausente y la reconexión tras un reinicio. Verifica el texto en el terminal y la
trama V2 en el servidor. Si el receptor aún conserva la definición GATT antigua
del PLC operativo, olvida esa vinculación en el terminal y vuelve a emparejarlo.


### Sesiones de mantenimiento compartidas

Desde el firmware 3.0.15-rc1, el PLC libera la sesión de mantenimiento tras
60 segundos sin recibir comandos. La app 1.3.28 envía una señal cada 15 segundos
mientras está visible, incluso con la actualización automática de estado
desactivada. Durante OTA se suspende el plazo. El receptor HID y el
emparejamiento Bluetooth conservan su configuración. Las apps anteriores
pueden perder la sesión tras un minuto sin enviar comandos.

Después de una conexión confirmada, la app guarda localmente el último PLC.
Puedes seleccionarlo como **Last PLC** y pulsar **Connect** aunque no aparezca
en el escaneo.


Con app 1.3.31 y PLC 3.0.17-rc1, **Disconnect** pide al propio PLC cerrar
el enlace Bluetooth de la tablet de mantenimiento. Esto libera la plaza para
el relevo incluso si Android mantiene abierto el enlace físico al cerrar GATT.
La orden se rechaza durante OTA. El respaldo por inactividad sigue disponible.

Los enlaces BLE sin rol se cierran tras 20 segundos. La ventana de asignación
HID y la OTA suspenden este cierre mientras están activas.
