"""Local WebSocket peer for CTest's public C DLL API scenarios.

Runs on 127.0.0.1 with an ephemeral port; Python provides an independent
SHA-1/accept oracle. No external server or Python dependencies are required.
"""
import base64
import hashlib
import json
import socket
import subprocess
import sys
import threading
import time

GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
STATES = (
    b'{"type":"states_response","data":{"joints":['
    b'{"joint_id":"index_proximal","angle":45,"load":0.25}]}}'
)
SCENARIOS = {
    "coalesced", "fragmented", "bad_accept", "bad_status",
    "missing_accept", "incomplete_handshake", "incomplete_frame",
    "masked_frame", "orphan_continuation",
}


def receive_exact(peer, length):
    result = bytearray()
    while len(result) < length:
        part = peer.recv(length - len(result))
        if not part:
            raise AssertionError(f"peer closed after {len(result)} of {length} bytes")
        result.extend(part)
    return bytes(result)


def client_text_frame(peer):
    header = receive_exact(peer, 2)
    assert header[0] == 0x81, f"not a final text frame: {header!r}"
    assert header[1] & 0x80, "client frames must be masked"
    length = header[1] & 0x7F
    if length == 126:
        length = int.from_bytes(receive_exact(peer, 2), "big")
    elif length == 127:
        length = int.from_bytes(receive_exact(peer, 8), "big")
    mask = receive_exact(peer, 4)
    payload = receive_exact(peer, length)
    return json.loads(bytes(c ^ mask[i % 4] for i, c in enumerate(payload)))


def frame(opcode, payload):
    assert len(payload) < 126
    return bytes((opcode, len(payload))) + payload


def serve(listener, scenario):
    with listener.accept()[0] as peer:
        peer.settimeout(4)
        request = bytearray()
        while b"\r\n\r\n" not in request:
            assert len(request) < 4096, "oversized client request"
            part = peer.recv(4096)
            assert part, "client closed before upgrade"
            request.extend(part)
        assert request.startswith(b"GET / HTTP/1.1\r\n")
        key_headers = [line.split(b":", 1)[1].strip()
                       for line in request.split(b"\r\n")
                       if line.lower().startswith(b"sec-websocket-key:")]
        assert len(key_headers) == 1
        key = key_headers[0]
        assert len(base64.b64decode(key, validate=True)) == 16
        accept = base64.b64encode(hashlib.sha1(key + GUID).digest())

        if scenario == "incomplete_handshake":
            peer.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n")
            return

        if scenario == "bad_accept":
            accept = b"AAAAAAAAAAAAAAAAAAAAAAAAAAA="
        status = (b"HTTP/1.1 200 OK" if scenario == "bad_status"
                  else b"HTTP/1.1 101 Switching Protocols")
        headers = status + b"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        if scenario != "missing_accept":
            headers += b"Sec-WebSocket-Accept: " + accept + b"\r\n"
        headers += b"\r\n"

        if scenario == "fragmented":
            # Deliberately split the HTTP header over distinct recv() calls.
            peer.sendall(headers[:17])
            time.sleep(0.02)
            peer.sendall(headers[17:])
        elif scenario == "coalesced":
            # A single write must preserve the post-upgrade frame as pending data.
            peer.sendall(headers + frame(0x81, STATES))
        else:
            peer.sendall(headers)

        if scenario in {"bad_accept", "bad_status", "missing_accept"}:
            return

        ping = client_text_frame(peer)
        joint = client_text_frame(peer)
        query = client_text_frame(peer)
        assert ping == {"type": "ping"}
        assert joint["type"] == "joint_control"
        assert joint["data"] == {
            "joint_id": "index_proximal", "angle": 45.0,
            "duration_ms": 200,
        }
        assert query["type"] == "get_states"

        if scenario == "fragmented":
            cut = len(STATES) // 2
            peer.sendall(frame(0x01, STATES[:cut]) + frame(0x80, STATES[cut:]))
        elif scenario == "incomplete_frame":
            peer.sendall(b"\x81\x05ab")
        elif scenario == "masked_frame":
            peer.sendall(b"\x81\x80")
        elif scenario == "orphan_continuation":
            peer.sendall(frame(0x80, b"x"))


def main():
    if len(sys.argv) != 3 or sys.argv[2] not in SCENARIOS:
        raise SystemExit("usage: socket_server.py CLIENT_EXECUTABLE SCENARIO")
    executable, scenario = sys.argv[1:]
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(5)
        failure = []

        def run_peer():
            try:
                serve(listener, scenario)
            except Exception as exc:
                failure.append(exc)

        thread = threading.Thread(target=run_peer, daemon=True)
        thread.start()
        try:
            client = subprocess.run(
                [executable, scenario, str(listener.getsockname()[1])],
                capture_output=True, text=True, timeout=9, check=False,
            )
        finally:
            thread.join(timeout=5)
        if thread.is_alive():
            raise AssertionError("loopback peer did not finish")
        if failure:
            raise AssertionError("loopback peer failed") from failure[0]
        if client.returncode:
            raise AssertionError(
                f"C client exited {client.returncode}:\n{client.stdout}{client.stderr}"
            )


if __name__ == "__main__":
    main()
