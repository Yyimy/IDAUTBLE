# Instrucciones de trabajo

- Este repositorio contiene dos proyectos coordinados: android/ y firmware/.
- Mantener los UUID FFE0/FFE1 y la trama binaria V2 salvo migración explícita de ambos componentes.
- Firmware validado con Arduino-ESP32 3.0.7; no actualizar el core o la API BLE sin adaptar y comprobar el código.
- Mantener el PIN de ejemplo en el código publicado. No introducir credenciales reales, keystores o local.properties en Git.
- La autorización actual es por conexión BLE; AUTH=OFF y desconexión/reinicio deben revocarla.
- La app de mantenimiento no confirma automáticamente eventos de negocio del SGA/WMS. ACKMODE=1 requiere un receptor que almacene y deduplique.
- Conservar los pines y la política conservadora de corte de baterías de esta versión hasta verificar el esquema físico. La fuente actual usa LED O0 y relé O1 del expansor activo bajo.
- Android: ejecutar desde android/ `./gradlew testDebugUnitTest assembleDebug` (JDK 17 y SDK 36).
- Firmware: ejecutar `python3 firmware/tests/test_logic.py`, `python3 firmware/tests/test_pin_constant.py` y la compilación Arduino CLI documentada en README.
- No afirmar validación en placa o teléfono real cuando solo se hayan ejecutado compilaciones o pruebas en host.
- Preparar cambios posteriores en una rama y describir en la pull request el comportamiento y las verificaciones. Evitar mezclar cambios de protocolo, hardware e interfaz sin necesidad.
