# PLC Scanner 3.0.4-rc1 — código completo corregido

Versión candidata para validación en banco. Derivada del archivo adjunto del 29/10/2026. Conserva los comandos binarios de cuatro bytes y la trama V2, con las diferencias de integración indicadas abajo. No se ha probado físicamente en la placa.

## Contenido y apertura

- `PLC_Scanner_v3/PLC_Scanner_v3.ino`: firmware completo.
- `PLC_Scanner_v3/Types.h`: tipos necesarios para el preprocesado de Arduino. Debe permanecer en la misma carpeta.
- `VALIDACION.md`: comprobaciones realizadas, límites y pruebas en placa pendientes.
- `tests/test_logic.py`: pruebas de regresión de funciones extraídas del propio firmware.

Desde la carpeta firmware/ del repositorio, abrir `PLC_Scanner_v3/PLC_Scanner_v3.ino` en Arduino IDE. No copiar únicamente el INO dejando atrás `Types.h`.

## Cambiar el PIN en el código

Al principio de `PLC_Scanner_v3.ino`, editar esta única línea:

```cpp
static constexpr char PLC_MAINTENANCE_PIN[] = "123456";
```

Sustituir las seis cifras por el PIN deseado, compilar con **Arduino-ESP32 3.0.7** y cargar el firmware. No hace falta borrar NVS ni cambiar otra variable. La compilación rechaza valores de longitud distinta o con letras.

Si se modifica el PIN, el firmware solicita retirar las vinculaciones BLE anteriores antes de anunciarse. En Android, **olvidar/desvincular el PLC y emparejarlo de nuevo** con el PIN nuevo. La app deberá usar también el nuevo valor en `AUTH=<pin>`. La configuración WiFi y los eventos pendientes se conservan. Si una retirada de vinculación o el registro de referencia falla, se informa por serie y se reintenta en el siguiente arranque; el PIN activo sigue siendo el del código.

La retirada de vínculos y el emparejamiento con un teléfono real siguen pendientes de prueba en placa. El PIN del código no modifica la contraseña del punto de acceso WiFi OTA. Esta entrega solo modifica el firmware; la adaptación Android queda para el siguiente paso.

## Entorno utilizado para compilar

- Paquete de placas **esp32 de Espressif, 3.0.7**.
- Librería **Adafruit ADS1X15, 2.5.0** y dependencia **Adafruit BusIO, 1.16.2**.
- BLE, WiFi, Wire, Preferences, HTTPClient y Update incluidos con el paquete ESP32.
- Ya no se necesita ESP32Ping.
- Placa de compilación: **ESP32S3 Dev Module**.
- USB Mode: **Hardware CDC and JTAG**. USB CDC On Boot: **Enabled**.
- Flash de compilación: **4 MB**, particiones **Minimal SPIFFS (1.9 MB APP with OTA)**.
- Elegir en el equipo real el tamaño/tipo de flash y PSRAM correspondientes a su placa. La compilación no identifica el hardware instalado.
- La partición de aplicación debe admitir el binario y existir una segunda partición OTA. No seleccionar Huge APP sin OTA.

Comando reproducible:

```bash
arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc,USBMode=hwcdc,PartitionScheme=min_spiffs PLC_Scanner_v3 --warnings all
```

Se entrega el fuente, no un binario para instalar indiscriminadamente en placas con configuraciones desconocidas.

## Primera conexión: cambios que afectan a la app

1. Abrir el monitor serie USB a **115200**. El comando de consola `info` vuelve a mostrar versión, PIN y contraseña del AP OTA.
2. El **PIN se define en el propio INO**, en `PLC_MAINTENANCE_PIN`, al principio del archivo. Se entrega `"123456"` como valor de ejemplo. Exactamente seis cifras entre comillas, incluidos ceros iniciales si se desean. El valor compilado se aplica siempre, incluso si NVS contiene un PIN anterior. El mismo PIN se usa para emparejamiento BLE y para autorizar mantenimiento. La contraseña del AP OTA sigue siendo independiente, de 16 caracteres y persistente por equipo.
3. Por defecto se exige **emparejamiento BLE con cifrado y MITM**, conservando las claves de vinculación. Introducir el PIN cuando Android/Windows lo solicite. Según el cliente, puede ser necesario iniciar el emparejamiento desde los ajustes de Bluetooth.
4. Conectar y habilitar las notificaciones en FFE1. El receptor debe concatenar los fragmentos recibidos: una notificación no equivale necesariamente a una trama. Se conserva ETX como final de trama V2 y CRLF para líneas de texto.
5. Para configurar o apagar, enviar por BLE `AUTH=123456` con el PIN real. Respuesta: `<ACK>AUTH=OK;TTL=SESSION\r\n`. La autorización no caduca por tiempo: dura hasta `AUTH=OFF`, desconexión BLE o reinicio del PLC. Cinco intentos incorrectos bloquean la autorización durante un minuto.
6. Las lecturas L/B/A/P/D/I/C no requieren AUTH adicional una vez emparejado. RTC, CFG, NET, WIFI, ALT, apagado O e inicio/aborto OTA sí lo requieren. Una app existente necesitará añadir ese paso de mantenimiento.

Los flags `REQUIRE_BLE_PAIRING` y `REQUIRE_MAINT_AUTH`, ambos a 1, controlan estas exigencias. No desactivarlos para una instalación sin valorar el acceso al equipo. Cambiar estos flags requiere recompilar.

`NET?` devuelve `PWD=***`, nunca la clave. Si la app reenvía literalmente `PWD=***`, el firmware conserva la clave existente. En instalaciones nuevas el SSID y contraseña WiFi están vacíos; la configuración previa en NVS se conserva si no se borra la flash.

## Correcciones incluidas

- TCP transmite aunque BLE esté desconectado. Las notificaciones se fragmentan según el MTU negociado, con un solo emisor desde el loop.
- Cola de ocho comandos FreeRTOS: se elimina la sobrescritura del comando pendiente. Se rechazan comandos sobredimensionados.
- Lector y altura mediante máquinas de estados: no se espera dos segundos dentro de una función bloqueante. La fotocélula se atiende entre pasos.
- Solo se acepta lectura de códigos terminada por LF. Límite de 320 caracteres; se rechazan controles, exceso de campos y prefijos desconocidos. Se mantiene la lectura parcial de palet o ubicación cuando el otro campo falta.
- Timeout de altura de 250 ms, limpieza previa de recepción y comprobación de rango de 0,2 a 20 m. Esta validación no sustituye comprobar el protocolo exacto del sensor.
- Filtrado de fotocélula de 250 ms y armado inicial estable de 1,5 segundos.
- Autoapagado a los **120 minutos de permanecer vacío**, desde la descarga o desde el arranque estable si ya estaba vacío. Mantener un palet cargado no consume ese plazo. No se autoapaga por inactividad durante OTA.
- El evento conserva el estado de fotocélula y la fecha/hora de su detección filtrada. La resolución temporal sigue siendo de un segundo; no se trata de una captura hardware con precisión de milisegundos.
- Supervisión de batería cada segundo, validez por canal y recuperación del ADC. Un error de medida no se interpreta como una batería retirada. La última medida queda en caché y DIAG indica si es válida.
- El 5 % queda como aviso. El corte utiliza tensión confirmada, evitando dos umbrales de corte contradictorios.
- Corte de relé solicitado, reintentado cada 500 ms y comprobado leyendo el registro de salida cuando sigue habiendo alimentación. Se preserva el aviso `<ACK>OFF\r\n`. La lectura del registro confirma la orden eléctrica del expansor, no la posición mecánica del relé.
- Configuración NET validada completa antes de aplicarse; puertos válidos, máscara contigua, host utilizable y gateway en subred. Eliminada la falsa prueba de disponibilidad por ping. La asignación de IP corresponde al administrador de red.
- Inicio WiFi sin bucle de espera de 15 segundos; reconexión y estado consultables. Volver a DHCP restablece expresamente la configuración DHCP. Un OK significa configuración aceptada, no servidor conectado: consultar NETSTAT.
- Parser TCP con resincronización, tramas partidas/agrupadas y líneas ACK/PONG. Las escrituras de datos TCP usan socket sin espera y reanudan los envíos parciales; una transmisión que no termina en dos segundos cierra esa conexión sin detener el ciclo de control.
- Validación de fechas, años bisiestos y bit de parada del oscilador RTC. Se reintenta detectar periféricos cada cinco segundos.
- OTA con tamaño exacto, SHA-256 hexadecimal, timeout de inactividad de datos de 10 segundos y limpieza del contexto hash también al abortar. La URL debe pertenecer a un cliente 192.168.4.2–254 del AP privado. El servidor HTTP de la tablet debe servir el binario directamente, preferiblemente con Content-Length y sin Transfer-Encoding chunked.
- Se bloquean cambios incompatibles durante OTA. Solo se inicia con fotocélula armada, sin palet y sin lecturas pendientes. Si se detecta una transición durante OTA, se solicita aborto y se registra un evento con códigos/altura desconocidos; no se inventan lecturas retrospectivas.

## Baterías: decisión explícita pendiente del circuito

Se conserva la calibración adjunta: `K_ADC_BAT1 = K_ADC_BAT2 = 0.0006435`. Debe comprobarse con multímetro en ambos canales. El porcentaje lineal entre 14 y 20 V es una estimación de tensión, no un medidor real de carga; el formato heredado alcanza 99 %.

**Por defecto se mantiene el corte si cualquiera de las baterías presentes está baja**:

```cpp
#define BATTERY_CUT_ONLY_WHEN_ALL_LOW 0
```

Cambiar a `1` permite seguir funcionando mientras quede una batería por encima del umbral. Hacerlo solo después de confirmar que cada batería está aislada/protegida y que la baja no puede descargarse por el otro pack. El software no identifica esa topología.

Corte por ≤13,5 V mantenidos tres segundos, o ≤14,0 V mantenidos diez segundos. Con muestreo cada segundo existe hasta aproximadamente un segundo de cuantización adicional, más las demoras del sistema. Si una medida es inválida se suspenden las confirmaciones de corte; debe existir protección eléctrica/BMS independiente. No se presenta este firmware como protección de batería certificada.

Se conservan los pines del archivo adjunto, incluyendo **LED en salida 0** y **relé en salida 1** del expansor activo bajo. No se han sustituido por asignaciones de versiones anteriores.

## Eventos sin conexión y confirmación opcional

Hay una cola persistente de **8 eventos completos**, almacenada en NVS. Se conserva el orden y se intenta guardar antes de transmitir. Si falla la escritura no se descarta el evento de RAM. Cada evento recibe un ID persistente.

- **Predeterminado: `ACKMODE=0`.** Se envía únicamente la trama V2 heredada. El evento sale de la cola tras aceptar su transmisión por TCP o tras emitir todos sus fragmentos BLE. Esto conserva compatibilidad, pero no prueba que la app lo haya guardado.
- **Modo fiable de aplicación: `ACKMODE=1`.** Activarlo con AUTH y cola vacía. El receptor recibe `EVT=<id>\r\n` seguido de la trama V2. Debe guardar la operación y responder `ACK=<id>\r\n` por TCP o `ACK=<id>` por BLE. Sin ACK se reintenta cada tres segundos, en orden. El receptor debe deduplicar por ID de equipo + ID de evento: un reintento puede ser duplicado.
- El ID de evento es de 32 bits; al agotarse se rechazan nuevos eventos con diagnóstico, sin reutilizar identificadores.
- Con ambas conexiones activas se distribuye a ambas; basta confirmación de una. No hay colas independientes por destino.
- Si la cola se llena se conserva lo anterior y se avisa `ERR=EVENT_QUEUE_FULL`; el nuevo evento no puede guardarse. DIAG muestra desbordamientos. Ocho eventos no cubren desconexiones prolongadas con trabajo continuo.
- Solo los eventos ya completados y guardados sobreviven al reinicio. Las lecturas en curso y los 16 trabajos pendientes están en RAM. Un corte durante la escritura o antes de completarse una lectura puede perder trabajo; no se promete entrega exactamente una vez.

## Comandos nuevos

| Comando | Uso |
|---|---|
| `AUTH=<pin>` | Autoriza mantenimiento durante la conexión BLE, sin límite de tiempo |
| `AUTH=OFF` | Revoca el mantenimiento y conserva consultas y conexión BLE |
| `INFO?` | Versión, protocolo, modo ACK y heartbeat |
| `DIAG?` | RTC/ADC/salidas, validez y voltaje de baterías, colas, desbordamientos y corte |
| `ACKMODE=0` / `ACKMODE=1` | Cambia la política de entrega; requiere AUTH y colas vacías |
| `ACK=<id>` | Confirma el evento de cabecera en modo ACK |
| `HEARTBEAT=0` / `HEARTBEAT=1` | Activa vigilancia de aplicación TCP; requiere AUTH y colas vacías |

El heartbeat está **desactivado por defecto** para no desconectar servidores anteriores. Al activarlo, el PLC envía `PING\r\n` cada 10 s y el servidor debe responder `PONG\r\n`. Se cierra TCP si pasan más de 20 s sin PONG. Los estados WIFI/LINK/TCP y el identificador de conexión se conservan; se corrige `CONNNECTED` a `CONNECTED`.

## Prueba inicial recomendada

Primera carga por USB, conservando una copia del firmware anterior. Si la placa anterior usa otras particiones, no actualizar a estas mediante OTA. Revisar GPIO, modelo de expansor, polaridad, calibración, flash y particiones. Para un primer banco se puede recompilar con `ENABLE_POWER_CUT=0`; volver a 1 después de comprobar el circuito. Seguir la matriz de `VALIDACION.md` antes de instalar en una carretilla operativa.

## Cambio en 3.0.2: autorización por conexión

La respuesta a un PIN correcto es `AUTH=OK;TTL=SESSION`. Se mantiene la autorización sin renovaciones periódicas mientras siga esa conexión BLE. `AUTH=OFF` la revoca incluso durante el bloqueo temporal por intentos fallidos. Un PIN incorrecto revoca una autorización previa. No se guarda la autorización en NVS; desconexión o reinicio siempre requieren una nueva autorización. La app puede recordar el PIN para facilitarla, pero no confunde recordar un PIN con mantener una sesión abierta.

Para aprovecharlo hay que actualizar tanto este firmware como la app 1.2-plc3. Conserva en `PLC_MAINTENANCE_PIN` el PIN que ya utilizas antes de cargar el fuente descargado. La plantilla sigue incluyendo 123456 como ejemplo.

`INFO?` continúa siendo una consulta, sin cambios de estado. Devuelve `FW` (versión de firmware), `PROTO` (formato de trama), `ACKMODE` (confirmación de eventos por aplicación) y `HEARTBEAT` (vigilancia PING/PONG del servidor TCP). No es una parte de la respuesta de C: la app lo consulta después del primer C confirmado de cada conexión.


## LED de estado (version 3.0.3)

Un solo LED, conectado a O0 del expansor activo bajo. No requiere cambios
Android ni cambia comandos, UUID, trama V2, calibración o política de corte.

| Estado | Patrón | Acción |
|---|---|---|
| Preparado | Encendido fijo | Listo, con la comunicación requerida disponible. |
| Sin conexión requerida | 500 ms ON / 500 ms OFF | Revisar BLE o TCP según el modo de trabajo. |
| Batería baja (<=20%, por encima de la banda muy baja) | Dos destellos cada 4 s | Preparar una batería cargada. |
| Batería muy baja (<=10%) | Tres destellos cada 2 s | Cambiar la batería cuanto antes. |
| Fallo importante o corte solicitado | 200 ms ON / 200 ms OFF | Consultar diagnóstico; puede apagarse. |
| OTA en curso | 800 ms ON / 200 ms OFF | Esperar a que termine la actualización. |

Los destellos de batería duran 200 ms y están separados por 200 ms apagados.
La prioridad es **fallo/corte > batería muy baja > batería baja > OTA > conexión > preparado**.
Un aviso de batería oculta temporalmente los estados de conexión y OTA.

- Si WiFi está habilitado, se exige TCP conectado; BLE de mantenimiento no oculta
  la falta del servidor. Si WiFi está deshabilitado, se exige BLE disponible para
  notificar (conectado, cifrado y con notificaciones habilitadas). No se exige
  autorización de mantenimiento para señalar preparado.
- Se utiliza el menor porcentaje entre las baterías presentes. Una tensión <=2 V
  se considera ausencia, coherentemente con el supervisor actual. Se admite una
  única batería. Si ambas medidas son válidas pero ambas baterías parecen ausentes,
  se indica fallo.
- Cada medida debe ser válida y tener como máximo 3 s de antigüedad. Un error de
  medida nunca se convierte en un aviso de batería agotada. También se indica
  fallo ante RTC no válido, fallo del expansor o solicitud de corte.
- El cambio de banda de batería se confirma durante 3 s. Tras entrar en <=10%,
  permanece en tres destellos hasta alcanzar >=13%. Tras el aviso de <=20%,
  se exige >=23% para volver al estado habitual, siempre con la misma confirmación.
  Las entradas iniciales de alarma son exactamente <=20% y <=10%.
- Los porcentajes son estimaciones por tensión. El corte sigue dependiendo de
  la tensión y de los tiempos del supervisor; el LED no retrasa ni modifica esa
  protección. Un corte puede ocurrir antes de completar un patrón luminoso.
- Preparado no certifica la lectura de códigos ni la recepción/almacenamiento de
  eventos en el SGA. Si falla el expansor o su alimentación, el LED puede no poder
  mostrar la alarma: el mismo hardware controla el indicador.

Pruebas en host: `python3 firmware/tests/test_led.py` desde la raíz del repositorio.
En placa: verificar visibilidad, polaridad O0, BLE/TCP, los dos niveles de batería,
retirada y sustitución de cada batería, fallo ADC, OTA y prioridades. No provocar
una descarga profunda de baterías para probar el corte; usar un banco adecuado.


## ID configurable de la carretilla (version 3.0.4)

App requerida para la interfaz: IDAUT BLE 1.3-plc3. El técnico autoriza con el PIN
y abre **ID de la carretilla**. Se admiten 1–20 caracteres ASCII: A-Z, números,
espacios interiores, guion y guion bajo. El firmware elimina espacios exteriores,
normaliza a mayúsculas y rechaza controles, `;`, `=`, Unicode y cualquier otro
carácter. Un ejemplo válido es `E 1234 BCD`.

- `ID?`: consulta sin AUTH. Devuelve `<ACK>ID=<actual>;HWID=<12 HEX>;NAME=<nombre Bluetooth>\r\n`.
- `ID=<nuevo>`: cambio por BLE con AUTH vigente. La misma respuesta confirma
  el valor tras guardarlo en NVS y solicitar la actualización del nombre GAP y
  del scan response. La API del controlador BLE es asíncrona; el anuncio real
  debe verificarse mediante una nueva búsqueda en el terminal.
- El nuevo ID entra en vigor para las tramas posteriores y se conserva al apagar.
  La conexión se mantiene; no se reinicia el PLC. Desconectar y volver a buscar
  para actualizar el nombre en las listas de búsqueda. Android puede conservar
  el nombre antiguo en su lista de vinculados; la app prioriza el scan response
  y confirma la identidad mediante `ID?`.
- El nombre personalizado es exactamente el ID, sin añadir `ISP-`. Sin ID
  personalizado se conserva el comportamiento anterior: ID=HWID y nombre ISP-HWID.
- HWID permanece inmutable para diagnóstico. Las credenciales de la app siguen
  asociadas a la dirección BLE. PIN, vínculos y clave/SSID OTA no se cambian.
  El SSID OTA utiliza siempre HWID, aunque se cambie la matrícula.
- Se rechaza el cambio con `ERR=ID_BUSY` si hay palet cargado, fotocélula sin
  estabilizar, corte solicitado, lecturas activas/encoladas, eventos pendientes
  o en tránsito, o un estado OTA distinto de IDLE. No se renombra ni descarta
  retrospectivamente ningún evento. En ACKMODE=1 los pendientes deben ser
  confirmados por el receptor de negocio antes de cambiar la identidad.
- Errores: `ERR=AUTH_REQUIRED`, `ERR=ID_INVALID`, `ERR=ID_BUSY`,
  `ERR=ID_STORAGE`, `ERR=ID_BLUETOOTH`. Si falla la aplicación Bluetooth o el
  guardado se intenta restaurar el nombre previo; no se confirma el cambio.
  Volver a consultar y, si persiste un fallo del controlador, reiniciar y comprobar.
- No se detectan IDs duplicados entre equipos; asignarlos de forma única en el
  inventario del cliente. El contador de eventos no se reinicia al cambiar el ID.

### Compatibilidad del receptor

Se conservan los bytes de control, separadores y orden V2. El campo ID pasa de
12 HEX a texto ASCII de 1–20 caracteres. El receptor debe extraerlo desde STX
hasta el primer ESC. No asumir una longitud fija ni validar únicamente HEX.
La app de mantenimiento acepta las tramas fragmentadas con ambos formatos.
Una integración externa puede necesitar adaptar su parser y su identificación
histórica de equipos antes de utilizar un ID propio.

Pruebas reproducibles: `python3 firmware/tests/test_identity.py` desde la raíz.
Pendiente en placa: nombre anunciado y leído por Android, guardado tras apagar,
rechazos bajo actividad, trama BLE/TCP y continuidad de emparejamiento y OTA.
