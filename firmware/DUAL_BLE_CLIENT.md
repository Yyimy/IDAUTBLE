# Dual BLE UART — experimental firmware v2

**Connections supported concurrently:** 1 GATT UART CLIENT and 1 Android MAINTENANCE client. The existing HID + maintenance configuration remains supported. The session table accommodates **2** peers total, not 3.

## Customer PC client handshake

1. Subscribe to NOTIFY on service `0000FFE0-0000-1000-8000-00805F9B34FB`, characteristic `0000FFE1-0000-1000-8000-00805F9B34FB` (encrypted, authenticated BLE pairing is required).
2. Write ASCII `CLIENT\r\n` to FFE1, NOT the maintenance command `ESC C CR LF`.
3. Expect `ACK CLIENT=READY CR LF`; only then is the peer recognized as a customer client.
4. The client receives business TX_BOTH frames (including scanner readings). Maintenance status/configuration replies remain private to Android.
5. The client can request readings with `ESC CMD CR LF` (`L` full, `B` battery, `A` height, `P` photoelectric sensor, `D` scan pallet, `I` scan location). Successful readings use the same V2 business route. Maintenance commands and outbox acknowledgements are reserved for their existing callers. Test with `ACKMODE=0`.

## Arduino IDE

Use **all** files from `firmware/PLC_Scanner_v3` on this branch, including every required `.h`, in one sketch directory, with matching `.ino` directory basename. Do not mix files from previous RC builds. Board package Arduino-ESP32 3.0.7.

## Bench test matrix
- Pair Windows and Android separately using secure BLE.
- Test order PC→Android and Android→PC.
- Confirm `CLIENT=READY` is received by the PC and maintenance commands still work on Android.
- Check identical complete business scan frames at both receivers; no Android-only replies on PC.
- Check disconnect and reconnect for each receiver.
- Check long fragmented frames, secure pair rejection and idle/advertising behavior.
- To alternate on the same Windows PC, enroll it as a HID receiver once and keep that assignment and Windows bond. The simulator can register CLIENT on that HID session after the current text finishes. Disconnecting from the simulator disables FFE1 notifications and lets the PLC restore the saved HID session. If the physical link closes, normal HID reconnection restores it.
- Use Forget receiver and Assign receiver when changing the assigned HID device. An authorized Assign receiver releases any remaining CLIENT link and reserves the slot for HID enrollment.
- To reassign the same PC after Forget receiver, leave it paired in Windows. During the current boot, the firmware can reuse previously confirmed HID subscriptions in a new authorized assignment when the PC authenticates with the same bond. A new bond or a client that explicitly disabled its reports still needs to enable its HID subscriptions.
- Validate with hardware and compiler before release.

**No claims of validated radio behavior or production readiness until bench testing succeeds.**
