# IDAUT BLE — adaptación al firmware PLC 3.0.5

Proyecto Android Studio situado en la carpeta android/ del repositorio. Se conserva la interfaz de terminal, red y OTA, el identificador `com.example.idautble` y los UUID FFE0/FFE1. Versión de la app: **1.3.3-plc3** (versionCode 7).

## Abrir y compilar

1. Clona el repositorio o actualiza tu copia local con Git.
2. En Android Studio, abre la carpeta `android`, que contiene `settings.gradle.kts`.
3. Deja que Gradle sincronice. Usa JDK 17 para Gradle y Android SDK Platform 36. Se mantienen Gradle 8.13, AGP 8.13.2 y Kotlin 2.0.21 del proyecto original.
4. Selecciona tu terminal y pulsa Run. También puedes generar el APK desde el menú Build de Android Studio.
5. Al compilar con tu Android Studio, conserva tus claves locales de firma. El repositorio no contiene claves ni APK preinstalables con una firma diferente.

Comprobación reproducible en Windows:

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

## Conexión y PIN

1. En el firmware, configura las seis cifras de `PLC_MAINTENANCE_PIN`, compila con el paquete ESP32 3.0.7 y carga ese firmware.
2. En la app, concede los permisos Bluetooth, activa Bluetooth, busca y conecta al PLC.
3. **Android muestra el diálogo de emparejamiento.** Introduce el PIN que has definido en el firmware. Si ya estaba vinculado y has cambiado el PIN, olvida/desvincula el PLC en los ajustes de Android y empareja otra vez.
4. Espera a que la app indique que el canal BLE está listo. La app verifica las notificaciones y recibe la línea completa `CONECTADO`.
5. Pulsa **PIN / Acceso**, introduce el mismo PIN y autoriza el mantenimiento. También aparece ese diálogo al solicitar el primer cambio protegido.
6. Puedes marcar **Recordar en este dispositivo**. El PIN se guarda por dirección BLE, cifrado mediante una clave de Android Keystore, y se excluye de copias de seguridad y transferencias entre dispositivos.

**No hay que escribir ni cambiar el PIN en el código de Android.** Se introduce en la app. Android conserva su propia vinculación Bluetooth; la opción “Recordar” de la app se utiliza para la autorización posterior `AUTH`, no para rellenar por su cuenta el diálogo de emparejamiento del sistema.

Las consultas y lecturas siguen disponibles una vez emparejado. Red, reloj, prefijos, activación de altura, apagado y OTA necesitan autorización. La app espera `AUTH=OK` antes de enviar esas acciones. Con el firmware 3.0.2 la autorización dura toda la conexión, sin límite de minutos, hasta pulsar **Bloquear acceso**, desconectar o reiniciar el PLC. Después de bloquear, una acción protegida exige tu confirmación aunque el PIN esté recordado. Tras una nueva conexión se puede facilitar la autorización con el PIN recordado.

“Bloquear acceso” envía `AUTH=OFF`, cancela cambios protegidos que aún no se hayan enviado y conserva el enlace para consultar datos. Una operación ya enviada no se puede deshacer. Si el bloqueo no se confirma, la app desconecta para revocar. “Olvidar PIN” elimina el valor guardado por esta app y solicita también el bloqueo; no cambia el PIN del firmware ni elimina el vínculo de Android. Los intentos incorrectos y el bloqueo temporal se muestran sin repetir automáticamente el PIN de forma indefinida.

Los comandos pendientes de autorización se cancelan al cerrar el diálogo o desconectar. Ante BUSY o un rechazo del PLC, revisa el mensaje y repite la operación cuando corresponda; no se repiten automáticamente acciones como apagado o inicio OTA.

## Red

- `NET?` puede devolver `PWD=***`. La pantalla indica que es una contraseña guardada.
- Al aplicar sin editar ese campo, la app **omite PWD**, conservando la clave existente en el PLC.
- Para cambiarla, escribe la nueva. **Dejar el campo vacío envía una contraseña vacía**; no significa conservarla.
- Los campos no admiten `;` ni saltos de línea, porque ese protocolo usa esos delimitadores.
- Configuración NET e indicadores NETSTAT se almacenan por separado. Si llegan en la misma ráfaga, no se pierde la configuración. Leer de nuevo la misma configuración también refresca los campos editados.
- Un OK significa que el PLC ha aceptado el comando; revisa WiFi, LINK y TCP para confirmar el estado de conexión.

## OTA

1. Selecciona el `.bin` y espera al cálculo SHA-256.
2. Inicia el servidor HTTP de la app.
3. Con las horquillas vacías y sin lecturas pendientes, pulsa Start OTA. Si hace falta, se solicita o renueva AUTH antes de enviarlo.
4. Abre los ajustes WiFi y conéctate al SSID `PLC_OTA_<ID>` que anuncia el equipo. Mantén la conexión aunque Android avise de que no tiene Internet.
5. **La contraseña de ese WiFi OTA es diferente del PIN BLE.** El firmware actual la muestra por USB, en el monitor serie a 115200, mediante `info`. La app no puede recuperarla automáticamente y no supone que siga siendo `12345678`.
6. Vuelve a la app y espera a `WAIT_PARAMS`. Upload Firmware solo se habilita con BLE conectado, servidor activo, IP de la red del PLC y ese estado.
7. Mantén la app en primer plano durante la carga. Se muestran progreso y errores reales del PLC. Tras DONE, espera al reinicio y a la reconexión. El servidor no se cierra al recibir DONE antes de que se produzca esa desconexión.
8. Abort envía `OTA!`, autorizado cuando corresponde. Si el firmware rechaza el inicio por no estar libre, la app lo comunica.

La detección de IP comprueba las redes WiFi disponibles, priorizando la subred `192.168.4.*`. Esto evita usar por error los datos móviles como referencia cuando el WiFi del PLC no tiene Internet. El servidor sigue entregando `firmware.bin` con tamaño fijo/Content-Length.

## Cambios internos relevantes

- Cola de escrituras GATT con respuesta y timeout; los comandos largos se envían como escritura fiable, sin partirlos en comandos independientes. Debe validarse con el teléfono real.
- Recepción reensamblada antes de interpretar mensajes: ETX para tramas V2 y CRLF para texto, con buffer acotado y resincronización.
- Se eliminan las lecturas de característica usadas como fallback que podían duplicar datos y no corresponden al FFE1 del firmware actual.
- Callbacks GATT procesados en el hilo principal y callbacks de conexiones antiguas descartados.
- PIN AUTH y contraseña WiFi redactados en TX, incluido el HEX. Log limitado a 1.000 entradas.
- Tratamiento de permisos antes de inicializar Bluetooth y tiempos de espera para emparejamiento/preparación del canal.
- No se envía ACK de eventos de negocio automáticamente. Esta app es de mantenimiento, no el SGA/WMS. Mantén `ACKMODE=0` salvo que otro receptor confirme esos eventos. INFO avisa si el PLC tiene ACKMODE=1.

## Validación

El proyecto ha compilado y generado el APK de depuración como comprobación. Las **19 pruebas unitarias han pasado**. Consulta `VALIDACION_ANDROID.md` para conocer el alcance y la matriz de pruebas físicas pendientes.

No se ha probado la conexión real con tu PLC, el emparejamiento del terminal, la persistencia real de Android Keystore ni una carga OTA física. La primera prueba debe hacerse en banco.

## INFO y comando C

C confirma que el PLC responde (`CONECTADO`). La app solicita `INFO?` una sola vez después de la primera confirmación de cada conexión. Pulsar C de nuevo no repite esa consulta; se puede enviar INFO? manualmente.

Ejemplo: `FW=3.0.2-rc1;PROTO=V2;ACKMODE=0;HEARTBEAT=0`.

- FW: versión del firmware del PLC, no de la app.
- PROTO: formato de la trama de datos V2.
- ACKMODE=0: no se exige ACK de aplicación para liberar eventos transmitidos; =1 requiere confirmación del receptor.
- HEARTBEAT=0: PING/PONG TCP desactivado; =1 activa la vigilancia del servidor. No es un indicador del estado BLE.

La app reconoce también una autorización temporal de firmware anterior y lo indica expresamente. Para eliminar el límite de cinco minutos hay que cargar el firmware 3.0.2; actualizar solo la app no cambia la política del PLC anterior.


## ID de la carretilla

1. Actualizar el firmware a 3.0.5 y la app a 1.3.3. Abrir en Arduino IDE la carpeta
   completa del sketch, incluyendo Types.h. Conservar el PIN propio antes de cargar.
2. Conectar al PLC. En TERMINAL, justo debajo de BINARY COMMANDS, desplegar
   **PIN / ACCESO** y pulsar **Introducir PIN / Autorizar**.
3. Sin lecturas, eventos u OTA pendientes (se admite un palet cargado), pulsar
   **Configurar ID** dentro de la sección **ID DE LA CARRETILLA**. La app consulta el ID real y muestra también HWID.
4. Introducir, por ejemplo, `E 1234 BCD` y pulsar **Guardar ID**. Se admite de
   1 a 20 caracteres A-Z, 0-9, espacios interiores, guion y guion bajo. Se
   normaliza a mayúsculas; no se permiten acentos ni separadores del protocolo.
5. Esperar a **ID guardado en el PLC**. Una escritura GATT completada no basta:
   la app espera la respuesta ID del PLC. Si no llega, muestra el timeout y
   permite volver a consultar. Un rechazo explica la causa sin afirmar guardado.
6. Desconectar y ejecutar Scan 5s: aparecerá exactamente el nuevo ID. No hace
   falta olvidar el vínculo Bluetooth ni introducir un PIN nuevo. El nombre de
   los ajustes de Android puede estar en caché; la app prioriza el anuncio y ID?.

El ID queda en el PLC y se utiliza en sus tramas. HWID y SSID OTA no cambian.
Asigna IDs distintos a cada equipo y adapta cualquier receptor externo que
espere 12 HEX: ahora el ID se lee hasta el primer ESC y puede tener 1–20
caracteres. La app de mantenimiento no convierte ni confirma eventos del SGA.


## Secciones plegables (app 1.3.1)

En TERMINAL, inmediatamente después de BINARY COMMANDS, aparecen:

1. **ID DE LA CARRETILLA**: ID actual y botón Configurar ID.
2. **PIN / ACCESO**: estado de mantenimiento y controles Introducir PIN / Autorizar,
   Bloquear acceso y Olvidar PIN en esta app.

Se retiran los controles de la parte superior. Cada tarjeta se abre o cierra
pulsando su cabecera o flecha, incluso sin conexión; los botones de operaciones
siguen deshabilitados sin conexión. Ambas empiezan plegadas y son independientes.
El ID y el estado del acceso siempre se muestran en las cabeceras. La elección
se mantiene durante los cambios de pestaña y las recreaciones de pantalla con
estado restaurado por Android. Plegar no bloquea el mantenimiento ni modifica
la configuración. Los diálogos de PIN y edición de ID conservan su funcionamiento.
No requiere actualizar el firmware si ya se utiliza 3.0.4.


## Cambio de ID con palet cargado (app 1.3.2 / firmware 3.0.5)

El ID puede modificarse con fotocélula a 1 o a 0, sin retirar el palet. Las
transiciones de la fotocélula siguen generando sus eventos normales; si se
está procesando una lectura o entregando eventos hay que esperar y repetir.
El diálogo y ERR=ID_BUSY ya no solicitan vaciar las horquillas. Este cambio
requiere firmware 3.0.5: actualizar solo la app no elimina el bloqueo anterior.
Las reglas OTA se mantienen.


## Resto del menú plegable (app 1.3.3)

Todas las secciones del panel izquierdo de TERMINAL se pueden plegar y desplegar
independientemente pulsando su cabecera, excepto BINARY COMMANDS, siempre visible:

- CONEXIÓN BLUETOOTH: búsqueda, conexión/desconexión y lista de dispositivos.
- ID DE LA CARRETILLA.
- PIN / ACCESO.
- HIGH MEASUREMENT.
- TEXT COMMANDS - AIM Code ID / RTC.
- MANUAL TERMINAL.

Conexión Bluetooth empieza abierta y las demás plegadas. La app conserva cada
estado al cambiar de pestaña y cuando Android restaura la pantalla. Las cabeceras
resumen el estado de conexión/altura o el contenido de la sección. Plegar una
sección no desconecta, no detiene la búsqueda ni cambia la autorización. El registro
de comunicaciones permanece visible a la derecha. No requiere actualizar firmware.
