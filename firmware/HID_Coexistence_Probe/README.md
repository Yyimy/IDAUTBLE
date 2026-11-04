# HID + mantenimiento: prueba de coexistencia 0.2.2

**Primer prototipo de banco, separado del firmware operativo PLC Scanner.**
La finalidad es verificar un teclado HID en un PC Windows o terminal Android y la
app Android de mantenimiento en **otro terminal**, con las dos conexiones activas.
El receptor recibe exclusivamente un texto sintético solicitado por el técnico.

Esta imagen **no ejecuta el controlador de la carretilla**: no supervisa baterías,
no controla relé/salidas, no lee sensores/códigos y no realiza OTA. Usar una placa
de banco alimentada por USB, fuera de la operativa de la carretilla. El firmware
operativo `../PLC_Scanner_v3/` permanece sin cambios. La app 1.3.23 añade los
controles del prototipo, visibles solo al detectar un firmware compatible.

## Preparar la placa

1. Usar ESP32-S3 con **Arduino-ESP32 3.0.7**, igual que el proyecto operativo.
2. Abrir `HID_Coexistence_Probe.ino`, con todos los archivos `.h` en la misma carpeta.
3. Mantener USB CDC habilitado y el esquema de partición **Minimal SPIFFS con OTA**.
   Conservar la configuración de flash/PSRAM de la placa; no borrar toda la flash.
4. Cargar por USB y abrir el monitor serie a 115200 con terminador de línea.
5. En 0.2.0 se anuncia por defecto como **PLC_BLE_AB** (anuncio neutro de diagnóstico).
   El PIN de muestra es **123456**,
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

## Corrección de suscripciones tras reinicio (0.2.1)

El caso observado en hardware es `SAVED=1;READY=0` con receptor seguro pero
`report=0 boot=0`. Android puede reconectar un vínculo ya emparejado sin volver a
escribir los CCCD: GATT establece que se conservan entre conexiones de clientes
bonded. 0.2.0 guardaba la identidad, pero reiniciaba esos indicadores por sesión.

0.2.1 guarda también los valores confirmados de notificación Report y Boot. Solo
se recuperan para el receptor con identidad/vínculo verificados y MITM. Una
escritura nueva de CCCD, incluida la desactivación, prevalece sobre el valor
recordado. No se habilitan suscripciones de otros terminales ni del canal de
mantenimiento. No hay envíos automáticos ni reproducción de pruebas pendientes.
Los cambios se guardan desde el loop, sin escribir flash dentro del callback BLE;
los valores sin cambios no provocan escrituras adicionales. La configuración
confirmada debe terminar de guardarse antes de apagar el equipo.

### Primera actualización desde 0.2.0

El registro antiguo conserva la identidad pero **no contiene suscripciones**.
Se lee sin inventarlas; aparece por USB `legacy subscriptions unknown`. Para
obtener valores nuevos de forma inequívoca, realizar una sola vez:

1. Cargar **HID-PROBE-0.2.1 o posterior** por USB, manteniendo anuncio neutro `0`.
2. Conectar la app, autorizar y pulsar **Forget receiver**.
3. En los ajustes Bluetooth del **terminal receptor**, olvidar solo `PLC_BLE_AB`.
   No olvidar el vínculo del terminal técnico.
4. En la app pulsar **Assign receiver** y volver a emparejar el receptor dentro
   de los 60 segundos. Debe aparecer `HID BOUND` y `SAVED=1;READY=1`.
5. Probar el texto, reiniciar el ESP32 y reconectar. Debe aparecer `HID RESTORED`
   y el estado debe recuperar las notificaciones sin repetir Assign.

Si un cliente vuelve a escribir sus CCCD por sí mismo, el registro antiguo se
actualiza al formato nuevo automáticamente. La secuencia anterior sirve para
los terminales que no los reescriben al reconectar, como el caso investigado.
El formato nuevo es v2; también se reconoce el registro vacío de v1. Un registro
corrupto o una máscara inválida no conceden notificaciones. El firmware 0.2.0 no
puede leer v2: al retroceder será necesaria una asignación nueva.

La app **1.3.23 sigue siendo compatible**; no hace falta reinstalarla. La nueva
recuperación debe validarse en los terminales físicos y en los dos órdenes de
conexión. Comprobar también desactivar una suscripción y que no se reactive sola.

Referencia normativa: [Bluetooth Core, GATT 3.3.3.3](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host/generic-attribute-profile--gatt-.html).

## Receptor guardado y recuperación (0.2.0)

Usar por defecto `PROBE_ADVERTISE_HID=0` y el nombre **PLC_BLE_AB**. El anuncio
neutro mantiene el servicio GATT HID, el PIN fijo y SC/MITM; en las pruebas del
usuario autenticó en ET40/Android 14, envió texto y coexistió con mantenimiento.
Las pruebas físicas de persistencia de esta versión están pendientes.

El prototipo guarda un solo receptor en la clave `receiver` del espacio NVS
**hid_probe**, separado de la configuración operativa. Es un registro versionado
con identidad/tipo y, si se distribuyó durante bonding, la IRK del receptor.
No guarda LTK nuevas ni modifica el almacenamiento de vínculos del stack. El
registro y las claves no se imprimen ni se devuelven a la app; solo su dirección
canónica. Proteger las copias de la flash igual que el almacenamiento Bluetooth.

- Para asignar, el candidato debe estar cifrado con MITM, suscrito a HID y tener
  identidad pública/estática válida en la base de vínculos. Una dirección privada
  observada no se guarda como si fuera identidad. Si hay dos candidatos, se
  rechaza por ambigüedad; conectar solo el receptor elegido.
- La recuperación comprueba el vínculo actual y la misma identidad/IRK. Resuelve
  RPA mediante `ah` (AES-128) cuando el stack devuelve una dirección privada.
  No basta con el nombre, una MAC anunciada o una conexión sin autenticar.
- Una identidad con claves cambiadas o sin vínculo no recupera el rol: volver a
  asignar mediante una acción autorizada. Si el terminal no distribuye identidad
  y usa una dirección privada, la asignación no se completa y vence a los 60 s.
- El rol de la app y su autorización nunca se guardan. Tras reiniciar, conectar
  la app y autorizar de nuevo antes de modificar o enviar un test.
- No se reproduce texto pendiente al reconectar. La recuperación no fuerza al
  terminal a conectarse: puede ser necesario pulsar Conectar en sus ajustes.

### Desde la app 1.3.23

1. Cargar 0.2.0 por USB e instalar la app actualizada.
2. Conectar la app en el terminal técnico. Se muestra **HID RECEIVER · PROTOTYPE**
   justo debajo de PIN / ACCESS. Pulsar Refresh para consultar sin modificar.
3. Autorizar mediante PIN / ACCESS y pulsar **Assign receiver**. Hay 60 segundos
   para conectar el receptor desde sus propios ajustes Bluetooth, en otro terminal.
4. Esperar **Receiver saved and ready**. Si el receptor ya estaba conectado y es
   el único candidato, se asigna inmediatamente.
5. Enfocar un campo de prueba vacío del receptor y pulsar **Send test text**.
   Comprobar el resultado; SUBMITTED no es confirmación de recepción de la app destino.
6. Para cambiar de receptor, **Forget receiver**, desconectar el anterior y asignar
   el nuevo. Forget elimina solo la asignación persistente, conserva los vínculos
   Bluetooth y cancela un texto en curso liberando su última tecla cuando es posible.

### Comandos (USB o terminal TXT de la app)

| Comando | Efecto |
|---|---|
| `HIDRECEIVER?` | Consulta SAVED, READY, WINDOW, STORE e ID canónica; nunca devuelve claves |
| `HIDPAIR=1` | Abre 60 s para elegir y guardar el único candidato seguro |
| `HIDPAIR=0` | Cancela la ventana; conserva la asignación y el receptor activo |
| `HIDFORGET=1` | Elimina la asignación guardada y retira el rol HID tras liberar teclas |
| `HIDTYPE=TEST` | Envía una prueba explícita al receptor listo |
| `status` (USB) | Estado y roles detallados; añade el registro HIDRECEIVER |

Las acciones requieren autorización por conexión BLE. USB es la vía local de
administración de banco. Un fallo de NVS responde `ERR=HID_STORAGE`; la app no
debe presentar una asignación como guardada sin confirmación. Fallo o carrera de
desconexión después de guardar: `ERR=HID_DISCONNECTED_AFTER_SAVE`; consultar el
estado antes de reintentar. El token/generación impide asignar un `conn_id` reciclado.

### Pruebas físicas pendientes

1. Asignar y enviar una vez desde la app con ambos terminales conectados.
2. Desconectar/reconectar solo el receptor: debe aparecer `HID RESTORED` y permitir
   un nuevo envío **sin HIDPAIR=1**. No debe repetirse el texto anterior.
3. Reiniciar el ESP32 y conectar receptor primero/app después; repetir en orden
   inverso. El receptor se recupera; la app exige PIN / ACCESS de nuevo.
4. Conectar un tercer terminal conocido o cambiar de receptor tras Forget: solo
   el elegido recibe teclas. Un desconocido no se convierte automáticamente en HID.
5. Forget, reiniciar y reconectar el antiguo receptor: permanece sin rol HID.
6. Probar rotación de dirección privada, reemparejamiento y Windows. Los tests en
   host incluyen un vector Core `ah`, pero no sustituyen estos casos físicos.

Comprobaciones adicionales:

```bash
python3 firmware/tests/test_hid_receiver.py
```

Necesita OpenSSL de desarrollo para AES en host. El ESP32 utiliza mbedTLS del core.

## Historial: diagnóstico A/B de anuncio (0.1.2)

Se ha observado en Zebra ET40 / Android 14 que el diálogo se cierra antes de
poder introducir el PIN, incluso desde los ajustes del sistema. El ESP32 registra
`fixed_pin_match=1` y fallo de autenticación `0x51`. En Windows se ha podido
introducir el PIN; aún no está confirmado el emparejamiento ni el envío HID allí.
Esta versión es una prueba de aislamiento, **no una solución confirmada**.

Al principio del `.ino` está el selector:

```cpp
#ifndef PROBE_ADVERTISE_HID
#define PROBE_ADVERTISE_HID 0
#endif
```

| Valor | Nombre | Anuncio |
|---|---|---|
| `0` (primera prueba) | `PLC_BLE_AB` | Apariencia genérica y UUID FFE0 |
| `1` | `PLC_HID_AB` | Apariencia de teclado y UUID 1812 + FFE0 |

**Ambos conservan exactamente los mismos servicios GATT, incluido HID, el PIN,
SC/MITM, bonding y las comprobaciones por sesión.** Se comparan los indicios del
anuncio, no la presencia del servicio HID. Se usan nombres y MAC distintas y nuevas
respecto a 0.1.0/0.1.1 para evitar reutilizar sus vínculos/caché. Los vínculos del
ESP32 siguen compartiendo el almacenamiento Bluetooth habitual; no se borran.

1. Hacer Pull y cargar por USB con valor `0`. Cerrar la app en el Zebra.
2. Confirmar en serie `HID-PROBE-0.1.2` y
   `mode=NEUTRAL_ADVERTISEMENT name=PLC_BLE_AB`.
3. Desde ajustes Bluetooth del Zebra, buscar **PLC_BLE_AB** e intentar emparejar
   con el PIN configurado (`123456` por defecto). Si ya se probó este modo,
   olvidar solo ese nombre antes de repetir.
4. Registrar desde `BLE CONNECT` hasta el resultado de `BLE SECURITY` y cualquier
   desconexión. Que el diálogo permita escribir no basta: el éxito exige
   `success=1 secure=1 matched=1`. Entonces probar la conexión desde la app.
5. Cambiar el selector a `1`, recompilar y cargar por USB. Repetir en el mismo
   Zebra con **PLC_HID_AB**. Si ya se probó, olvidar solo ese nombre.
6. Comparar con Windows, anotando si autentica, no solo si deja escribir el PIN.
   Probar un único terminal a la vez durante esta comparación.

Si el neutro autentica y el HID no, queda implicado el camino que activa el
anuncio de teclado. Si ambos fallan, esta prueba no descarta el servicio HID ni
identifica al responsable: habría que inspeccionar el intercambio SMP/HCI. Si
ambos autentican con las identidades nuevas, tampoco demuestra por sí solo que
el problema anterior esté corregido: los vínculos/caché pueden haber influido.

Se añaden logs de solicitud de seguridad y confirmación numérica, sin imprimir
el PIN. Una confirmación numérica inesperada se registra y mantiene el criterio
anterior de aceptación; no se acepta automáticamente para sortear el fallo.
No se cambia la app ni el firmware operativo y no se cambia a Just Works.

En 0.1.2 se compararon ambos anuncios. Para las pruebas actuales de 0.2.0 usar
el valor `0`, que ya ha enviado texto al ET40. `STATUS?` continúa respondiendo
`ERR=PROBE_ONLY` por diseño.

## Historial: corrección de emparejamiento (0.1.1)

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

Para nuevas pruebas, usar **0.2.0 y el procedimiento de receptor guardado de arriba**. El nombre
anterior era `PLC_HID_TEST`; ya no se utiliza en las dos variantes de diagnóstico.

El nuevo log incluye `success`, `secure`, `matched` y `auth_mode`; el motivo solo
se muestra cuando hay un fallo. Una conexión válida debe mostrar `success=1`,
`secure=1`, `matched=1` y después `CONECTADO` en la app. `fixed_pin_match=1`
confirma que el PIN de la notificación coincide con el configurado sin imprimirlo.
Si vuelve a fallar, registrar desde BLE CONNECT hasta BLE DISCONNECT, incluidos
los nuevos campos. El código hexadecimal por sí solo no demuestra un PIN mal escrito.

## Prueba recomendada: app primero

1. Con `PROBE_ADVERTISE_HID=0`, desde nuestra app Android buscar **PLC_BLE_AB**, emparejar y conectar.
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
   **PLC_BLE_AB** desde sus ajustes Bluetooth. El servicio HID se descubre tras conectar.
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

El dispositivo expone servicios GATT HID y mantenimiento incluso con anuncio
neutro. Algunos Android pueden reconocerlo también como teclado en el terminal técnico; si
ocultan el teclado en pantalla, habilitarlo en los ajustes del teclado del terminal.
Eso no concede a esa conexión el rol receptor. Anotar este comportamiento en la prueba.

## Teclado primero y reconexiones

También se puede probar el orden inverso usando el monitor USB:

1. Enviar `HIDPAIR=1` por USB y emparejar **solo el receptor**.
2. Esperar a `HID BOUND`; conectar después la app desde el otro terminal.
3. Autorizar la app y ejecutar `HIDTYPE=TEST`.

Desde 0.2.0, la identidad del receptor se guarda y su rol se recupera tras una
reconexión autenticada y suscrita. La app sigue necesitando autorización nueva
por conexión. No es necesario repetir HIDPAIR=1 para el mismo receptor guardado. No abrir la
ventana para dos receptores candidatos a la vez: este prototipo admite uno.

- Al desconectar HID, se cancela el texto pendiente sin repetirlo al reconectar.
- Al desconectar o bloquear el mantenimiento que inició un texto, se cancela y
  se intenta liberar la última tecla si la conexión HID lo permite.
- Una desconexión HID no revoca el PIN del técnico ni borra sus suscripciones.
- Una nueva conexión reutilizando el mismo `conn_id` recibe otra generación;
  el trabajo de la sesión anterior queda invalidado.
- `HIDPAIR=0` cancela la ventana de asignación. `HIDFORGET=1` cancela el texto
  y retira la asignación/rol tras liberar la última tecla.
- Una tercera conexión se rechaza. Un fallo de cifrado cierra esa conexión.
- Tres PIN de mantenimiento incorrectos bloquean esa sesión hasta reconectar.
  Es una política del prototipo, no una sustitución de la política operativa.

## Diagnóstico TCP corregido (0.2.2)

En Arduino-ESP32 3.0.7, NetworkClient no sobrescribe el valor por defecto cero de
`Print::availableForWrite()`. Hasta 0.2.1, el saludo se enviaba con println, pero
los estados y resultados se condicionaban a ese valor y se descartaban.

0.2.2 usa para los tres tipos de mensaje una cola volátil de ocho líneas de hasta
192 bytes. Cada vuelta realiza como máximo un envío de 128 bytes con
`MSG_DONTWAIT`, conservando el resto de una escritura parcial y reintentando
EAGAIN/EWOULDBLOCK/EINTR sin esperar dentro del socket. Un error fatal o cinco
segundos sin progreso cierran la conexión y descartan lo pendiente. La pérdida
de WiFi, una reconexión TCP y NET/WIFI=0 también limpian la cola. Una cola llena
muestra un diagnóstico por USB; no bloquea la lectura HID ni promete entrega.
No es un outbox persistente de negocio, no interpreta ACK y no reproduce teclas.

La prueba con backpressure se ejecuta así:

```bash
python3 firmware/tests/test_hid_tcp.py
```

### Repetir la prueba de coexistencia

1. Cargar 0.2.2 por USB y confirmar INFO?. Conservar anuncio neutro `0` y la
   asignación HID de 0.2.1: no se modifica el formato NVS ni hace falta olvidar
   dispositivos o reinstalar la app.
2. Reconectar ambos terminales. Confirmar APP=1;HID=1 y autorizar mantenimiento.
3. Configurar NET una sola vez por USB (los parámetros de red desaparecen al
   reiniciar). Esperar a WIFI=1;TCP=1; no repetir NET mientras sigue conectando.
4. Comprobar en servidor el saludo, estados cada 10 s y
   `HID_PROBE_TEST;HIDTEST=SUBMITTED;TEXT=PALLET123<TAB>LOC01<ENTER>` tras una
   prueba iniciada desde la app. El receptor debe recibir realmente el texto.
5. Repetir durante 15 minutos con envíos en los minutos 0, 5, 10 y 15, guardando
   estados del PLC y registros del servidor. La validación física de 0.2.2 está
   pendiente; los tests en host no la sustituyen.

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
