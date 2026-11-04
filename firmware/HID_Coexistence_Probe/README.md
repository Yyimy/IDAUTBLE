# HID + mantenimiento: prueba de coexistencia 0.1.1

**Primer prototipo de banco, separado del firmware operativo PLC Scanner.**
La finalidad es verificar un teclado HID en un PC Windows o terminal Android y la
app Android de mantenimiento en **otro terminal**, con las dos conexiones activas.
El receptor recibe exclusivamente un texto sintético solicitado por el técnico.

Esta imagen **no ejecuta el controlador de la carretilla**: no supervisa baterías,
no controla relé/salidas, no lee sensores/códigos y no realiza OTA. Usar una placa
de banco alimentada por USB, fuera de la operativa de la carretilla. El firmware
operativo `../PLC_Scanner_v3/` y la app de mantenimiento no cambian en esta fase.

## Preparar la placa

1. Usar ESP32-S3 con **Arduino-ESP32 3.0.7**, igual que el proyecto operativo.
2. Abrir `HID_Coexistence_Probe.ino`, con ambos archivos `.h` en la misma carpeta.
3. Mantener USB CDC habilitado y el esquema de partición **Minimal SPIFFS con OTA**.
   Conservar la configuración de flash/PSRAM de la placa; no borrar toda la flash.
4. Cargar por USB y abrir el monitor serie a 115200 con terminador de línea.
5. El dispositivo se anuncia como **PLC_HID_TEST**. El PIN de muestra es **123456**,
   configurable mediante `PROBE_PIN` (exactamente seis dígitos).

La prueba utiliza una identidad MAC local derivada de la MAC de fábrica, solo en
RAM, para diferenciar su caché GATT de la imagen operativa. El almacenamiento de
vínculos Bluetooth del ESP32 sigue siendo el predeterminado: al restaurar el firmware
operativo **puede ser necesario volver a emparejar** los terminales. No se borran ni
se escriben los espacios de configuración/contadores del PLC desde este código.

**Restauración:** cargar de nuevo `firmware/PLC_Scanner_v3/` por USB con la
misma partición. El prototipo no ofrece OTA: mantener el acceso USB durante toda
la prueba. Si falla un vínculo tras restaurar, volver a emparejar ese terminal.

Compilación reproducible desde la raíz del repositorio:

```bash
arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc,USBMode=hwcdc,PartitionScheme=min_spiffs --warnings all firmware/HID_Coexistence_Probe
python3 firmware/tests/test_hid_probe.py
```

Se usa la clase HID incluida en el core, sin instalar otra biblioteca ni actualizar
el core. Los envíos utilizan `esp_ble_gatts_send_indicate` con conexión explícita.
Se evita [`BLECharacteristic::notify()` de 3.0.7](https://github.com/espressif/arduino-esp32/blob/3.0.7/libraries/BLE/src/BLECharacteristic.cpp),
que recorre los clientes conectados y comparte el estado de su descriptor CCCD.

## Corrección de emparejamiento (0.1.1)

En 0.1.0 se solicitaba cifrado desde `BLEDevice` y otra vez desde el callback de
conexión. Además, la app Android inicia su propio `createBond()`. El log
`earlier enc was not done for same device` muestra que ya había una petición en
curso. 0.1.1 deja el inicio del emparejamiento al terminal central (Android o host
HID), mediante nivel `0` para el arranque automático y sin repetir la
petición desde `onConnect`.

Las características y descriptores de datos conservan permisos cifrados MITM.
Las sesiones solo se habilitan tras autenticar con MITM; el PIN y la autorización
por conexión siguen siendo necesarios. El nivel `0` aquí no habilita
escrituras ni pulsaciones sin autenticar.

Si ya se intentó conectar con 0.1.0:

1. Cargar 0.1.1 por USB y reiniciar la placa.
2. En el terminal de mantenimiento, olvidar **solo PLC_HID_TEST** en los ajustes
   Bluetooth. Mantener el receptor HID desconectado durante este primer intento.
3. Volver a buscar desde la app y pulsar Conectar. Introducir el PIN configurado
   (123456 en el ejemplo) y esperar a `CONECTADO`.
4. Autorizar después en PIN / ACCESS para enviar HIDPAIR/HIDTYPE, como se indica
   abajo. El diálogo de Android y la autorización de mantenimiento son pasos distintos.

El nuevo log incluye `success`, `secure`, `matched` y `auth_mode`; el motivo solo
se muestra cuando hay un fallo. Una conexión válida debe mostrar `success=1`,
`secure=1`, `matched=1` y después `CONECTADO` en la app. `fixed_pin_match=1`
confirma que el PIN de la notificación coincide con el configurado sin imprimirlo.
Si vuelve a fallar, registrar desde BLE CONNECT hasta BLE DISCONNECT, incluidos
los nuevos campos. El código hexadecimal por sí solo no demuestra un PIN mal escrito.

## Prueba recomendada: app primero

1. Desde nuestra app Android, buscar **PLC_HID_TEST**, emparejar y conectar.
   Usar el PIN del prototipo. El nombre/MAC son diferentes de los del PLC operativo.
2. Esperar a `CONECTADO` en TERMINAL. Las consultas automáticas `STATUS?` reciben
   `ERR=PROBE_ONLY`: es esperado; la prueba no presenta mediciones ficticias como
   si fueran datos del PLC. Se conservan PIN / ACCESS y el terminal manual.
3. Autorizar en **PIN / ACCESS**. En el terminal manual, modo TXT, enviar:

   ```text
   HIDPAIR=1
   ```

   La app añade CRLF automáticamente. Aparece `HIDTEST=PAIR_WINDOW_60S`.
4. En el **otro dispositivo** (PC Windows o Android receptor), emparejar
   **PLC_HID_TEST** desde sus ajustes Bluetooth como teclado.
5. Esperar a `HIDTEST=BOUND` en la app o `HID BOUND` por USB. La asignación solo
   se hace durante la ventana y a una conexión cifrada con suscripción HID. La
   conexión que ya pertenece a mantenimiento nunca se convierte en receptor.
6. En el receptor, enfocar dos campos de una aplicación de prueba, o un editor
   vacío donde se puedan observar TAB y ENTER. Desde la app enviar:

   ```text
   HIDTYPE=TEST
   ```

   Usar distribución de teclado española o inglesa para esta primera prueba y
   anotar el idioma del host. El receptor debe recibir **una sola vez**:

   ```text
   PALLET123<TAB>LOC01<ENTER>
   ```

   Se envían pulsación y liberación, a intervalos de 30 ms. Se prueba modo report
   y se contempla boot protocol; el estado Caps Lock se lee por conexión cuando
   el host lo comunica. Esta fase usa letras/dígitos/TAB/ENTER, sin configurador
   de idioma, campos reales ni caracteres especiales.
7. La app del técnico debe recibir solo los mensajes `HIDTEST=...`, **nunca las
   pulsaciones**. Consultar `HIDTEST?` en el terminal manual para ver el resumen.

`SUBMITTED` indica aceptación local de los informes BLE; no confirma que el
programa receptor los haya recibido íntegramente ni guardado. La prueba no envía
ACK de eventos de negocio y no inyecta tramas V2 sintéticas al SGA.

El dispositivo anuncia los servicios HID y mantenimiento juntos. Algunos Android
pueden reconocer también el dispositivo como teclado en el terminal técnico; si
ocultan el teclado en pantalla, habilitarlo en los ajustes del teclado del terminal.
Eso no concede a esa conexión el rol receptor. Anotar este comportamiento en la prueba.

## Teclado primero y reconexiones

También se puede probar el orden inverso usando el monitor USB:

1. Enviar `HIDPAIR=1` por USB y emparejar **solo el receptor**.
2. Esperar a `HID BOUND`; conectar después la app desde el otro terminal.
3. Autorizar la app y ejecutar `HIDTYPE=TEST`.

Los roles se guardan **solo durante la conexión**. Tras desconectar el receptor,
volver a abrir la ventana `HIDPAIR=1` desde la app autorizada o USB y reconectarlo.
La prueba no guarda todavía una identidad de receptor permanente. No abrir la
ventana para dos receptores candidatos a la vez: este prototipo admite uno.

- Al desconectar HID, se cancela el texto pendiente sin repetirlo al reconectar.
- Al desconectar o bloquear el mantenimiento que inició un texto, se cancela y
  se intenta liberar la última tecla si la conexión HID lo permite.
- Una desconexión HID no revoca el PIN del técnico ni borra sus suscripciones.
- Una nueva conexión reutilizando el mismo `conn_id` recibe otra generación;
  el trabajo de la sesión anterior queda invalidado.
- `HIDPAIR=0` detiene la prueba y retira el rol de teclado tras liberar la tecla.
- Una tercera conexión se rechaza. Un fallo de cifrado cierra esa conexión.
- Tres PIN de mantenimiento incorrectos bloquean esa sesión hasta reconectar.
  Es una política del prototipo, no una sustitución de la política operativa.

## WiFi y TCP opcionales

Para comprobar coexistencia, configurar **por USB** una red y un servidor de prueba:

```text
NET=SSID=TEST_NETWORK;PWD=example-password;HOST=192.0.2.10;PORT=9000
```

Sustituir todos los valores por los del entorno de pruebas. HOST debe ser una
IPv4 literal del servidor; esta fase evita DNS para acotar la espera de conexión. No copiar credenciales
reales al repositorio ni a una incidencia. La configuración vive solo en RAM, usa
DHCP y no modifica la configuración NVS de red del PLC. Límite de orden USB:
384 bytes de búfer (la orden anterior debe caber completa, sin saltos de línea).
Los campos no pueden contener `;`.

El servidor de prueba recibe un saludo `HID_PROBE=CONNECTED`, estado periódico
cada 10 s y el resultado de cada texto `HID_PROBE_TEST;...`. No procesa comandos
remotos ni ACK de negocio. `WIFI=0` por USB desactiva esa red de prueba.

`status` por USB muestra conexiones, generaciones, roles, cifrado, notificaciones,
MTU, autorización y heap libre/mínimo. El nivel de batería del servicio HID es
**100 % simulado**, no una medida de las baterías del equipo.

## Matriz de validación física pendiente

| Escenario | Windows receptor | Android receptor |
|---|---|---|
| App primero; emparejar HID después | Pendiente | Pendiente |
| HID primero; conectar app después | Pendiente | Pendiente |
| Solo el receptor recibe PALLET123/TAB/LOC01/ENTER | Pendiente | Pendiente |
| Diez solicitudes manuales: una salida por solicitud | Pendiente | Pendiente |
| Desconectar/reconectar app sin perder HID | Pendiente | Pendiente |
| Desconectar HID a mitad del texto; sin repetición | Pendiente | Pendiente |
| Bloquear PIN a mitad del texto; liberar tecla | Pendiente | Pendiente |
| Mantener ambas conexiones con WiFi/TCP activos durante 15 min | Pendiente | Pendiente |
| Recuperar publicidad al desconectar un terminal | Pendiente | Pendiente |
| Intento de tercer cliente | Pendiente | Pendiente |

Registrar modelo y versión de sistema de ambos terminales, orden de conexión,
resultado, log USB desde el arranque y log de la app. En el receptor es necesario
verificar el contenido exacto; el mensaje SUBMITTED por sí solo no es suficiente.

Las pruebas host verifican las clases utilizadas por el sketch (roles, PIN,
suscripciones, generación, pulsación/liberación, cancelación, congestión y
aritmética temporal). La compilación verifica las API del core 3.0.7. Ninguna de
las dos sustituye la matriz física anterior.

## Integración posterior en el PLC operativo

La siguiente fase, tras validar la coexistencia, incorporará el registro de
sesiones al firmware operativo, vinculación persistente del receptor, selector
APP/HID en la app, formato de lecturas reales, idioma/terminadores y adaptación
READY/OTA. El prototipo actual no ofrece ese selector ni reemplaza la app.
