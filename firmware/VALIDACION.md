# Validación — PLC Scanner 3.0.5-rc1

Fecha: 29 de octubre de 2026.

## Comprobado en este entorno

**Compilación y enlazado completos correctos**, con `--warnings all`, sin advertencias en la compilación final del sketch entregado.

| Parámetro | Resultado |
|---|---|
| Objetivo | ESP32-S3 Dev Module, USB CDC habilitado, Minimal SPIFFS con OTA |
| Arduino-ESP32 | 3.0.7 |
| ADS1X15 / BusIO | 2.5.0 / 1.16.2 |
| Tamaño de programa | 1.612.885 bytes, 82 % de 1.966.080 bytes de partición |
| Datos globales estáticos | 71.100 bytes, 21 % de 327.680 bytes |
| Pruebas de regresión en host | 97 aserciones de regresión + 5 comprobaciones de compilación del PIN + 48.041 comprobaciones del LED + 65 de identidad |

La memoria libre indicada por el compilador no mide el consumo dinámico de BLE/WiFi, colas y tareas durante el funcionamiento. Debe medirse en placa en una prueba prolongada.

Las pruebas del LED ejecutan las funciones reales con reloj y salidas simulados: seis patrones, duración de pulsos y pausas, prioridad de fallos/batería/OTA/conexión, ambos canales y retirada de batería, antigüedad y validez de medidas, umbrales 10/20%, confirmación de 3 s, recuperación a 13/23% y desbordamiento de millis(). Ejecutar `python3 firmware/tests/test_led.py` desde la raíz del repositorio. La visibilidad y los tiempos efectivos con I2C deben comprobarse en placa.

Las pruebas en host extraen las funciones del INO entregado y las ejecutan con reloj, UART, transporte, GPIO y acceso RTC simulados. No se limitan a buscar textos ni a reproducir la implementación en Python. Cubren:

- Autorización que permanece tras dos días simulados, revocación explícita, PIN incorrecto, bloqueo temporal, revocación durante bloqueo y rechazo del canal TCP para controlar la sesión BLE.
- PIN compilado que prevalece sobre NVS, conservación de la clave OTA, detección de cambio, solicitud de retirada de vínculos y reintento si falla. Se ejecutan las funciones reales con NVS y BLE simulados.
- Validación del PIN: seis cifras y ceros iniciales admitidos; longitud incorrecta o letras rechazadas al compilar.
- Enteros con desbordamiento, signos, texto y límites.
- Configuración NET inválida sin aplicación parcial; puertos, claves enmascaradas, subred y máscara.
- Fechas imposibles, año bisiesto y caracteres no numéricos.
- Códigos en orden invertido, lectura parcial, prefijos desconocidos y exceso de campos.
- Parámetros OTA básicos, tamaño y URL.
- Lectura asíncrona, rechazo al timeout sin LF, exceso de longitud y timeout de altura.
- Estado inicial de fotocélula y descarga tras mantener un palet por más tiempo que la inactividad.
- Corte por tensión confirmado, suspensión por lectura inválida y exclusión de batería ausente.
- TCP sin BLE, fragmentación BLE según MTU, reanudación de escrituras TCP parciales y cierre ante contrapresión persistente.

Para repetirlas: Python 3 y g++ en Linux/macOS/WSL; ejecutar `python3 tests/test_logic.py` desde la raíz del paquete. Ejecutar también `python3 tests/test_pin_constant.py` para comprobar las cinco configuraciones de PIN. Las pruebas no cargan nada en la placa. Los dobles de prueba no demuestran el comportamiento de las librerías, el enlace radio ni los tiempos reales de flash/I2C.

Las 65 comprobaciones de identidad ejecutan validación, carga y guardado NVS,
normalización, permisos BLE, rechazo por actividad, fallos simulados de las APIs
GAP/scan response y de almacenamiento, recuperación tras arranque y trama V2
con matrícula. Las APIs radio y NVS son dobles de prueba; no validan la radio
ni la memoria física. Ejecutar `python3 firmware/tests/test_identity.py`.

## Pruebas pendientes en la placa real

| Prueba | Criterio de aceptación |
|---|---|
| ID configurable | Guardar matrícula, volver a buscar, comprobar nombre exacto, trama BLE/TCP, reinicio y persistencia, PIN/vínculo conservados y mismo SSID OTA; aceptar con palet cargado o vacío; rechazar con lectura o eventos pendientes |
| Arranque y USB/UART0 | La consola USB no se mezcla con PON; lector a 9600, RX44/TX43 |
| LED y prioridades | Verificar fijo, lento, doble/triple destello, rápido y OTA; batería oculta conexión/OTA, fallo oculta batería, cambio de batería recupera patrón tras confirmación |
| Expansor de salidas | LED O0 y relé O1 coinciden con el cableado y la polaridad del adjunto |
| Fotocélula | LOW/HIGH corresponden a vacío/cargado y 250 ms filtran sin perder maniobras válidas |
| Carga/descarga con lector lento | Se capturan transiciones mientras otra lectura espera; revisar si retrasar una segunda lectura sigue siendo válido para la operativa |
| Sensor altura desconectado | La app recibe '-' y el equipo sigue atendiendo eventos |
| Calibración baterías | Cada canal coincide con multímetro en varios puntos y bajo carga |
| Batería única/cambio en caliente | No aparece una medida inválida como una batería retirada; comprobar el criterio de corte con el circuito real |
| ADC o bus I2C averiado | DIAG refleja el fallo y el equipo intenta recuperar sin presentar datos nuevos como válidos |
| Corte del relé con fallo I2C | Se reintenta; revisar también protección eléctrica independiente |
| BLE | Mantener autorización más de cinco minutos, bloquear manteniendo consultas, desconectar/reiniciar para revocar; cambio de PIN en el INO con NVS previo, retirada de vínculos antiguos, olvido en Android, emparejamiento con PIN, reconexión y suscripción; MTU 23 y MTU 185 sin pérdida ni mezcla de tramas |
| App/HTML actual | Añadir AUTH de mantenimiento y confirmar reensamblado de fragmentos antes de desplegar |
| TCP independiente | Sin ningún cliente BLE, los eventos y comandos llegan al servidor |
| Pérdida WiFi/servidor | Reconexión y recuperación de eventos guardados; inspeccionar duplicados |
| Outbox y reinicio | Encolar eventos, reiniciar y recuperarlos en orden; llenar 8 y comprobar diagnóstico del noveno |
| ACKMODE=1 | Guardar antes de confirmar; deduplicar reintentos; probar ACK perdido, repetido e incorrecto |
| Configuración de red | STATIC/DHCP, AP no disponible, clave errónea y cambios rechazados sin alterar la configuración anterior |
| Inactividad | 120 minutos desde vacío; no apagado inmediato tras descargar un palet retenido mucho tiempo |
| OTA | Éxito, hash incorrecto, tamaño incorrecto, descarga truncada, falta de datos, aborto y restauración de WiFi |
| Corte de alimentación durante OTA | Recuperación según bootloader y particiones reales; no se afirma rollback automático de aplicación |
| Trabajo continuo | Turno completo con lecturas, BLE+WiFi y reinicios; monitorizar heap y latencia máxima |

## Límites y decisiones conservadas

- Versión candidata, no validación de producto en campo.
- La app 1.3 añade la configuración de ID. Los receptores externos/HTML deben admitir el campo ID de longitud variable antes de usar una matrícula.
- El criterio de corte con dos baterías conserva el comportamiento conservador hasta comprobar su aislamiento/protección. No se presupone que sean intercambiables eléctricamente.
- Un ADC averiado no puede garantizar protección por tensión; no sustituye BMS ni protección hardware.
- El registro del expansor no acredita el movimiento de contactos del relé.
- La cola persistente es finita: 8 eventos terminados. No asegura conservar un evento que aún no ha terminado de medirse o escribirse cuando desaparece la alimentación.
- ACKMODE=0 conserva V2, pero no confirma almacenamiento en la app. ACKMODE=1 exige adaptar el receptor y deduplicar.
- Un ACK por un destino libera el evento para ambos canales. No hay confirmación independiente por servidor y tablet.
- La supervisión WiFi es asíncrona; las llamadas I2C, NVS, BLE y el intento TCP con timeout de 200 ms todavía tienen duración propia. La resolución DNS también puede añadir latencia si se configura HOST por nombre. No se certifica un tiempo de ciclo máximo ni tiempo real duro.
- Hay confirmación de integridad SHA-256, emparejamiento BLE y autorización de mantenimiento. No se implementa firma del fabricante, Secure Boot, Flash Encryption, TLS para el servidor TCP ni validación/rollback de aplicación tras arrancar una actualización. Son trabajos adicionales de aprovisionamiento e integración, no propiedades que pueda garantizar este INO.
- Una respuesta OK a NET/WIFI significa que el comando se ha aceptado. LINK/TCP se comprueban después con NETSTAT.
- Los contadores originales siguen siendo persistentes por actualización. No se ha cambiado su exactitud por un guardado diferido que pudiera perder conteos al apagar.

No instalar en una carretilla operativa sin completar las pruebas de alimentación, lectura y comunicaciones del equipo real.


## Regresión 3.0.5: cambio de ID con fotocélula a 1

Las pruebas de identidad ahora verifican cambio y persistencia con PaletCargado=true,
conservación de ese estado y rechazo con eventos pendientes también estando cargado.
Siguen comprobando estabilización inicial, AUTH, lecturas, OTA y corte. No se
cambia ni simula ningún modo que desactive la fotocélula. Pendiente de comprobar
en el equipo real la configuración con palet apoyado en el suelo y los eventos
producidos por personas que pasan ante el sensor.
