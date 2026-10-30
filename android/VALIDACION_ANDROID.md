# Validación de la adaptación Android — 30/10/2026

Resultado: **BUILD SUCCESSFUL** al ejecutar `testDebugUnitTest assembleDebug lintDebug`.

- Aplicación: `com.example.idautble` (identificador conservado).
- VersionCode: 13. VersionName: `1.3.9-plc3`.
- Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.0.21, JDK 17.
- Compile SDK 36, target SDK 36, min SDK 26. Se conservan las versiones del proyecto original.
- APK de depuración generado correctamente como comprobación de compilación. Este paquete entrega el proyecto fuente: compilarlo en tu Android Studio mantiene tus claves locales de firma.
- 39 pruebas unitarias, 0 errores, 0 fallos, 0 omitidas.

## Pruebas ejecutadas

| Clase | Pruebas | Fallos |
|---|---:|---:|
| PlcProtocolTest | 12 | 0 |
| NetParsingTest | 6 | 0 |
| EquipmentIdentityTest | 7 | 0 |
| DeviceStatusTest | 8 | 0 |
| DeviceStatusVisualsTest | 5 | 0 |
| ExampleUnitTest (original) | 1 | 0 |

Los casos de protocolo prueban todos los puntos de corte de la confirmación de conexión y de una trama V2, tamaños de fragmento 1/20/182 bytes, varios mensajes concatenados, UTF-8 partido, límites del buffer, resincronización, comandos protegidos/consultas, PIN con ceros iniciales, caducidad de sesión y redacción de secretos en el log. Las pruebas de red comprueban conservación de contraseña enmascarada, cambio explícito, contraseña vacía, valores con `=`, estados LINK/TCP independientes y recepción de NET/NETSTAT en una misma ráfaga.

Son pruebas de lógica JVM y compilación; no simulan ni certifican el cifrado radio, los controladores Bluetooth, la pantalla, Android Keystore o una actualización OTA real.

Las siete pruebas de identidad comprueban caracteres/límites, normalización,
autorización para ID=, consulta ID? libre, interpretación estricta de la respuesta,
nombre de fábrica y personalizado, rechazo de respuestas incompletas/contradictorias
y fragmentación de líneas ID y tramas con matrícula. No hay prueba instrumentada
del diálogo ni del controlador BLE; quedan para el terminal real.

## Comprobaciones pendientes en el equipo

0. ID de carretilla: consulta, guardado confirmado, rechazo por actividad, timeout, búsqueda con nombre nuevo, reconexión, reinicio, PIN recordado y OTA con SSID original.
1. Primera instalación y permisos en Android 11 o anterior y Android 12 o posterior, si se utilizan ambas familias.
2. Emparejar con el PIN del firmware: correcto, incorrecto, cancelación y cambio de PIN tras olvidar el vínculo antiguo.
3. Consultar datos sin AUTH. Solicitar una configuración: debe aparecer el diálogo y enviarse después de AUTH=OK.
4. Recordar/no recordar PIN; desconectar/reconectar; probar un segundo PLC para verificar la separación por dirección BLE.
5. Mantener autorización por sesión más de cinco minutos; pulsar Bloquear acceso, consultar sin autorización y exigir confirmación al configurar de nuevo. Desconectar/reconectar. Probar también firmware anterior con TTL numérico, PIN incorrecto y cancelar sin aplicar cambios pendientes.
6. Leer red: PWD=***, aplicar sin cambiar clave, sustituirla y cambiar DHCP/STATIC. Comprobar estados WiFi/LINK/TCP y rechazos del firmware.
7. Tramas largas, BLE con MTU 23 y 185, escrituras largas fiables y pérdida/reconexión de enlace.
8. OTA con horquillas vacías: emparejar, autorizar, servidor HTTP, WiFi del PLC sin Internet, WAIT_PARAMS, carga, hash, reinicio y reconexión. No cerrar el servidor antes del reinicio.
9. OTA abortado, archivo incorrecto, conexión caída y terminal con datos móviles activos. Mantener la app en primer plano durante la transferencia.
10. Revisar visualmente la interfaz en la tablet real y confirmar que el teclado y el diálogo de Android no ocultan el flujo necesario.

## Límites conservados

- Esta app de mantenimiento no es el registro de operaciones del SGA/WMS: no envía ACK de eventos automáticamente. Usar ACKMODE=0 salvo que un receptor integrado confirme esos eventos. La app avisa si INFO informa ACKMODE=1.
- El PIN del firmware y la contraseña WiFi OTA son valores distintos. La app no puede obtener automáticamente la contraseña OTA, que el firmware actual publica por USB con `info`.
- “Olvidar PIN” borra el valor recordado y solicita AUTH=OFF. “Bloquear acceso” no borra el PIN, pero no reautoriza silenciosamente en esa conexión. No se elimina el vínculo de Android. Si no se confirma el bloqueo, se desconecta.
- La compilación conserva avisos de APIs de compatibilidad y de componentes de la interfaz original obsoletos. La ejecución de Lint y sus resultados se detallan en la revisión 1.3.6; no hay pruebas instrumentadas en un dispositivo.
- Se mantienen los UUID FFE0/FFE1. Esta revisión requiere firmware 3.0.4 para configurar el ID (la autorización por sesión está disponible desde 3.0.2).

Las dos pruebas añadidas comprueban que una autorización por sesión sigue vigente tras cien días simulados, se revoca con reset y conserva compatibilidad con TTL numérico; y que INFO se solicita una sola vez aunque se repita C, habilitándose otra consulta al iniciar una conexión nueva. El flujo real de Bloquear acceso y AUTH=OFF sigue pendiente de validación en el PLC.


## Reorganización de interfaz (1.3.1)

Los controles de ID y PIN se trasladan a dos tarjetas plegables independientes,
justo debajo de BINARY COMMANDS en TERMINAL. Los valores de expansión utilizan
rememberSaveable fuera del contenido condicional de pestañas. Las cabeceras
muestran el ID y estado del acceso también plegadas; incluyen semántica de botón,
estado plegado/desplegado y etiqueta de acción para accesibilidad.

Comprobación: testDebugUnitTest y assembleDebug con el mismo entorno. Se conservan
las 26 pruebas existentes; no se añaden pruebas de lógica para esta reorganización.
Pendiente de revisión visual en tablet: posición, apertura independiente, desplazamiento,
restauración al girar/cambiar pestaña, lectura de las cabeceras y los tres controles
PIN. No se afirma validación instrumentada de la interfaz.


## Mensajes de cambio de ID (1.3.2)

El diálogo y el rechazo ID_BUSY explican que se permite un palet cargado y que
se deben terminar las operaciones pendientes. Compilación y 26 pruebas unitarias
comprobadas con testDebugUnitTest assembleDebug. Requiere firmware 3.0.5 para
eliminar el bloqueo por PaletCargado; la condición OTA no se modifica.
Pendiente de comprobar el flujo en la tablet y el PLC reales.


## Menú plegable (1.3.3)

Se aplica CollapsibleSettingsSection a conexión Bluetooth (incluida la lista de
dispositivos), altura, comandos de texto y terminal manual. ID y PIN ya utilizan
el mismo componente. BINARY COMMANDS conserva su tarjeta siempre abierta. Los
estados de expansión son independientes y usan rememberSaveable fuera del contenido
condicional de pestañas. No cambia la lógica de BLE ni los callbacks de los controles.

Verificación: testDebugUnitTest assembleDebug, 26 pruebas sin fallos. No se añaden
pruebas que repliquen la estructura de esta reorganización. Pendiente en tablet:
abrir/cerrar cada sección, desplazamiento y teclado del terminal, lista de equipos,
persistencia al girar o cambiar de pestaña y comandos binarios siempre accesibles.


## Binarios plegables y flujo de ID (1.3.4)

BINARY COMMANDS usa el mismo componente plegable y estado independiente que el
resto del menú. Empieza abierto. PIN / ACCESO se coloca inmediatamente después
de BINARY COMMANDS y antes de ID DE LA CARRETILLA.

Configurar ID comprueba la autorización antes de abrir la ventana y antes de
consultar ID?. Si falta, únicamente muestra un Toast indicando que primero se
introduzca la contraseña en PIN / ACCESO; no abre el diálogo de PIN ni la edición.
Se conserva la comprobación al guardar por si se pierde la autorización. La
ventana se cierra solo al recibir una respuesta de identidad válida que coincide
con el ID pendiente de guardar. Rechazos y timeout mantienen visible el error.
El resultado confirmado permanece en el registro.

Pendiente en tablet: plegado de binarios, orden PIN/ID, aviso sin autorización,
autorización y guardado correcto con cierre, rechazo y timeout sin cierre de éxito.
No se han ejecutado pruebas instrumentadas en un teléfono o PLC.


## Textos propios en inglés (1.3.5)

Se traducen menús, botones, diálogos, validación de campos, ayudas de red y OTA,
mensajes BLE, estado de autorización, diagnóstico y accesibilidad. Se revisan
los literales para distinguir UI de comandos y respuestas del protocolo. No se
traduce CONECTADO ni los bytes que aparecen en el registro de tráfico real.

La eliminación del PIN recordado tras ERR=AUTH se indica con el parámetro
incorrectPin, en lugar de buscar una palabra española en un mensaje traducible.
Se preserva su comportamiento y la autorización por conexión.

Pendiente en tablet: revisar longitudes y saltos de línea, permisos/emparejamiento,
PIN correcto/incorrecto/recordado, edición de ID con y sin acceso, errores de red,
flujo OTA y lectura por accesibilidad. No hay pruebas instrumentadas de interfaz.


## Estilo de mantenimiento y permisos GATT (1.3.6)

Se añaden botones compactos de color y un indicador textual verde/gris del acceso.
Se mantienen las condiciones enabled de las acciones y el flujo de PIN/ID. El
contraste del texto blanco sobre azul, verde y naranja es al menos 4,6:1; los
controles deshabilitados siguen la atenuación del tema.

Los dos close() de callbacks y executeReliableWrite() de la captura pasan por
helpers con comprobación de permiso y captura explícita de SecurityException.
Desconexión, cancelación de escritura y envío desde cola también gestionan la
revocación. No se añaden SuppressLint para ocultar esos avisos; se retiran las
supresiones de los métodos de desconexión y fallo de escritura.

`python3 android/tests/test_gatt_permissions.py` compila y ejecuta las tres
funciones reales extraídas de MainActivity con el compilador Kotlin 2.0.21 de la
caché de Gradle y dobles de BluetoothGatt. Sus 14 comprobaciones cubren ausencia
de permiso, operación correcta, revocación entre comprobación y llamada, rechazo
del stack y cierre de una conexión ya cerrada. Requiere Python 3 y JDK 17;
JAVA_HOME y GRADLE_USER_HOME seleccionan los mismos recursos que Gradle.
No prueba los diálogos del sistema ni el controlador Bluetooth de Android.

Pendiente en tablet/PLC: revisión visual con modo claro/oscuro, botones habilitados
y deshabilitados, indicador de acceso y revocación real de permiso durante conexión
y escritura larga. La protección física del PLC no se modifica.


Resultados de esta revisión: `testDebugUnitTest assembleDebug lintDebug` correctos,
APK generado, 26 pruebas JVM sin fallos ni errores y 14 comprobaciones de los
helpers de permisos superadas. Lint 31.13.2 no informa errores ni MissingPermission.
Se mantienen advertencias de dependencias/APIs/recursos de la base, sin actualizar
librerías ni cambiar el target SDK en esta revisión.


## Device status (1.3.7)

Nueva sección plegable debajo de DEVICE ID. Consulta al completar handshake,
tras confirmar un cambio de ID y al pulsar Refresh status; requiere canal BLE
preparado, pero no autorización de mantenimiento. Espera hasta 6 s y muestra
aviso de conexión/compatibilidad si no llega respuesta. El resto de la app
sigue disponible con firmware anterior. No envía ACK de eventos ni sondea
continuamente. La instantánea se borra junto con el transporte al desconectar.

8 pruebas nuevas comprueban un slot ausente, medidas inválidas/antiguas, rechazo
de datos parciales/duplicados/esquemas desconocidos, NaN/rangos, banda al 15 %,
corte diferenciado, edad monotónica, recepción fragmentada y coexistencia con
tramas V2 y carácter de solo lectura. También se superan las 14 aserciones host
de permisos GATT de la versión anterior.

Pendiente en Android físico: aspecto/scroll del panel desplegado, desconectar
y cambiar de carretilla, retirar cada batería, probar firmware anterior,
consultar durante actividad/OTA y comparar el motivo mostrado con el LED real.
La comprobación del expansor no acredita el accionamiento mecánico del relé.

Resultado final 1.3.7: `testDebugUnitTest assembleDebug lintDebug` correctos,
34 pruebas JVM sin errores ni fallos, APK generado y Lint sin errores ni
MissingPermission ni avisos en los archivos nuevos de Device status.
Se mantienen 31 advertencias y 3 avisos informativos de la base.


## Panel visual de estado (1.3.8)

DEVICE STATUS muestra tarjetas con círculo animado del patrón LED, barras de
batería, porcentaje destacado, tensión y edad de muestra. Las conexiones y
periféricos usan indicadores circulares con su etiqueta y estado textual.
Se conserva toda la información del diagnóstico y la sección plegable.

El parpadeo usa la cadencia de ledPatternOn del firmware 3.0.6; su fase es una
vista previa basada en la recepción, no está sincronizada con la placa. Se
pausa al plegar/abandonar pestaña y se detiene al caducar la instantánea. Sin
datos recientes todos los indicadores quedan neutros, manteniendo las últimas
lecturas identificadas como Last snapshot. No se añaden consultas automáticas.
Los porcentajes ausentes/inválidos/antiguos no se presentan como 0 %.

Las 5 pruebas nuevas comprueban ventanas y fronteras de los seis patrones,
repetición de periodos y tiempos grandes, pausa con datos antiguos, estados
sin porcentaje útil, límites de color 10/20 %, y caducidad tras 15.000 ms con
reloj monotónico. Los textos acompañan al color; el lector de pantalla no recibe
anuncios por cada pulso ni un porcentaje falso de la barra vacía.

Pendiente en terminal real: revisar panel a distintas anchuras y tamaños de letra,
temas claro/oscuro, fase/cadencia percibida del círculo, plegado y navegación,
TalkBack y contraste en pantalla industrial. Se han previsto disposición vertical
de baterías en panel estrecho/fuente grande y paleta adaptada al tema.
No se ha ejecutado ni medido la interfaz en un teléfono/tablet físico.

Resultado final 1.3.8: `testDebugUnitTest assembleDebug lintDebug` correctos,
39 pruebas JVM sin fallos/errores y APK 1.3.8-plc3 (versionCode 12) generado.
Lint: 0 errores, 0 MissingPermission y ningún aviso en los archivos DeviceStatus;
se mantienen 31 advertencias y 3 avisos informativos de la base. `git diff --check`
correcto. No cambia el firmware, la versión del protocolo ni las dependencias.


## Panel compacto y lectura durante un minuto (1.3.9)

LED 54→22 dp; porcentaje 26→16 sp en la misma línea que el nombre de batería;
rellenos de panel/batería 10→6 dp y separaciones internas 6→2 dp.
Altura de línea base explícita de 13 sp (ID 15 sp, porcentaje 18 sp). Las conexiones
pasan de seis tarjetas a tres filas de dos indicadores; anchuras pequeñas/fuente
grande usan una columna. La cabecera agrupa ID, firmware, edad y Refresh.
Se elimina el texto redundante tras una recepción válida; los errores siguen visibles.

Diagnostic details es plegable y conserva edades de muestra ADC, estado de cada
medida y explicaciones del patrón/umbrales. Los valores operativos y motivos de
fallo permanecen en la vista principal. La caducidad de la instantánea pasa a
60.000 ms con reloj monotónico; después sigue visible en gris y se pausa el LED.
El test de frescura verifica que sigue vigente después de 15 s, en 60 s exactos
y que caduca después de 60 s. No cambia la frecuencia de consultas ni firmware.

Pendiente en tablet real: revisar reducción del desplazamiento, ajuste de filas
con ambas baterías y distintos tamaños de fuente, acceso a Diagnostic details,
animación durante un minuto y datos antiguos. No se ha medido altura renderizada
ni ejecutado la interfaz en un dispositivo físico.

Resultado final 1.3.9: `testDebugUnitTest assembleDebug lintDebug` correctos.
39 pruebas JVM sin fallos/errores, APK 1.3.9-plc3 (versionCode 13) generado.
Lint sin errores ni MissingPermission; ningún aviso en DeviceStatus. Permanecen
31 advertencias y 3 avisos informativos de la base. `git diff --check` correcto.
