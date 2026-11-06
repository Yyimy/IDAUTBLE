# Prueba de teclado BLE

Sketch de banco para ESP32-S3 con Arduino-ESP32 3.0.7. Cárgalo por USB en una
placa de pruebas o con el PLC fuera de servicio. Este sketch no controla el
lector, la fotocélula ni el relé. Usa una dirección Bluetooth distinta de la
del firmware operativo. Conserva el emparejamiento original de IS-P.

1. Abre `HID_Keyboard_Bench.ino` en Arduino IDE. Selecciona **ESP32S3 Dev Module**
   y habilita **USB CDC On Boot**. Si usas el USB nativo, selecciona
   **Hardware CDC and JTAG** en USB Mode.
2. Carga el sketch y abre el monitor serie a **115200 baudios**, con **Nueva línea**.
3. En Windows, añade **IS-P Keyboard Bench** desde Ajustes de Bluetooth. Si
   Windows pide un PIN, introduce `123456`.
4. Abre el Bloc de notas. Envía `status` y después `test` desde el monitor
   serie. Durante `test`, pon el foco en el Bloc de notas. Una forma de hacerlo
   es usar el monitor serie en otro equipo. Si usas el mismo PC, el sketch
   espera tres segundos antes de escribir `hid test`.
5. Apaga y enciende el Bluetooth de Windows, espera a que se reconecte y
   repite `status` y `test`. Conserva el emparejamiento entre ambos intentos.

El monitor registra cada apertura, autenticación, suscripción HID y cierre.
`TEST SUBMITTED` indica que el ESP32 entregó los informes a su pila BLE.
Comprueba la recepción en el Bloc de notas.
