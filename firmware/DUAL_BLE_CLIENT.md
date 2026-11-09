# Dual BLE UART client + maintenance (experimental)

This branch preserves the existing FFE0/FFE1 BLE UART and the Android maintenance protocol, and adds an **explicit, read-only** UART client role for PC customer integrations.

## Setup
- FFE0 service, FFE1 characteristic (WRITE, WRITE_NR and NOTIFY).
- A secured, MITM-authenticated GATT connection must subscribe to FFE1 notifications.
- PC client writes the exact ASCII bytes `CLIENT\r\n` to FFE1.
- A successful registration returns `ACK CLIENT=READY CR LF` on its own connection.
- PC client must not send `ESC C CR LF`: that command is the maintenance handshake.
- Android maintenance connects as before and can issue its normal commands.
- Business frames (TX_BOTH, including outbox events) go to the client and to maintenance when available; maintenance responses use TX_BLE_ONLY and are never mirrored.

## Capacity and limitations
The current session table has **two** entries. Supported combination: `CLIENT + MAINTENANCE`. Existing `HID keyboard + MAINTENANCE` remains supported. Not supported: `CLIENT + HID + MAINTENANCE` simultaneously. Disconnect an existing HID receiver on Windows/Android before connecting a customer UART test client.

This patch is intended for bench validation, not production deployment. Validate secure pairing, bidirectional role isolation, MTU fragmentation, disconnect/reconnect, inverted connection order, idle expiry, client absence, OTA mode and event ACK modes before merging. The client is intentionally receive-only and cannot acknowledge events or request a read; test with ACKMODE=0 and initiate real scanner reads or scans via maintenance.

## Test
`python -m pytest firmware/tests/test_dual_ble_client.py`

Host tests validate session policy only; they cannot prove actual radio coexistence, Arduino compilation or Windows GATT interoperability.
