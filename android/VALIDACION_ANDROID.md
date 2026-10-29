# Validación de la adaptación Android — 29/10/2026

Resultado: **BUILD SUCCESSFUL** al ejecutar `testDebugUnitTest assembleDebug`.

- Aplicación: `com.example.idautble` (identificador conservado).
- VersionCode: 4. VersionName: `1.3-plc3`.
- Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.0.21, JDK 17.
- Compile SDK 36, target SDK 36, min SDK 26. Se conservan las versiones del proyecto original.
- APK de depuración generado correctamente como comprobación de compilación. Este paquete entrega el proyecto fuente: compilarlo en tu Android Studio mantiene tus claves locales de firma.
- 26 pruebas unitarias, 0 errores, 0 fallos, 0 omitidas.

## Pruebas ejecutadas

| Clase | Pruebas | Fallos |
|---|---:|---:|
| PlcProtocolTest | 12 | 0 |
| NetParsingTest | 6 | 0 |
| EquipmentIdentityTest | 7 | 0 |
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
- La compilación conserva avisos de APIs de compatibilidad y de componentes de la interfaz original obsoletos. No hay errores de compilación; no se afirma una ejecución de Android Lint ni pruebas instrumentadas en un dispositivo.
- Se mantienen los UUID FFE0/FFE1. Esta revisión requiere firmware 3.0.4 para configurar el ID (la autorización por sesión está disponible desde 3.0.2).

Las dos pruebas añadidas comprueban que una autorización por sesión sigue vigente tras cien días simulados, se revoca con reset y conserva compatibilidad con TTL numérico; y que INFO se solicita una sola vez aunque se repita C, habilitándose otra consulta al iniciar una conexión nueva. El flujo real de Bloquear acceso y AUTH=OFF sigue pendiente de validación en el PLC.
