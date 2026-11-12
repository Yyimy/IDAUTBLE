from pathlib import Path
import socket
import tempfile
import threading

from servidor_plc import Server

with tempfile.TemporaryDirectory() as directory:
    messages = []
    settings = (True, "PONG;test", True, "ACK={id}", "PING")
    server = Server("127.0.0.1", 0, Path(directory) / "events.db",
                    lambda *event: messages.append(event),
                    lambda: settings)
    endpoint = server.listener.getsockname()
    worker = threading.Thread(target=server.run)
    worker.start()
    try:
        with socket.create_connection(endpoint, timeout=1) as previous:
            previous.sendall(b"PING\r\n")
            assert previous.recv(100) == b"PONG;test\r\n"
            previous.sendall(b"EVT=77\r\n\x062026")
            # The previous client stays open and silent, without EOF or a reset.
            with socket.create_connection(endpoint, timeout=1) as current:
                current.sendall(b"CONNECTED;ID=IS-P\r\nPING\r\n")
                assert current.recv(100) == b"PONG;test\r\n"
                try:
                    assert previous.recv(100) == b""
                except ConnectionResetError:
                    pass
                frame = b"\x062026-11-12T16:00:00\x02IS-P\x1b90\x1b01\x1b0\x1bPAL\x1bLOC\x1b\x03"
                current.sendall(b"EVT=78\r\n" + frame)
                assert current.recv(100) == b"ACK=78\r\n"
        with socket.create_connection(endpoint, timeout=1) as after:
            after.sendall(b"PING\r\n")
            assert after.recv(100) == b"PONG;test\r\n"
        settings = (True, "0", True, "ACK={id}", "1")
        with socket.create_connection(endpoint, timeout=1) as custom:
            custom.sendall(b"1\r\n")
            assert custom.recv(100) == b"0\r\n"
        assert any("Sustituida" in message for _, message in messages)
    finally:
        server.stop()
        worker.join(2)
        assert not worker.is_alive()
print("PASS: half-open replacement, parser reset, PONG, ACK and normal reconnection")
