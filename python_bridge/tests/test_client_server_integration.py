"""Integration tests for aero_ws_python client/server in fake mode."""

import asyncio
import os
import socket
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from aero_ws_python.client import AeroWebSocketClient  # noqa: E402
from aero_ws_python.server import AeroWebSocketServer  # noqa: E402


def _get_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        sock.listen(1)
        return sock.getsockname()[1]


def _start_server_in_thread(server: AeroWebSocketServer):
    ready = threading.Event()

    def runner():
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        runner.loop = loop
        ready.set()
        loop.run_until_complete(server.start())
        loop.close()

    runner.loop = None
    thread = threading.Thread(target=runner, daemon=True)
    thread.start()
    ready.wait(timeout=2.0)
    return thread, lambda: runner.loop


def test_client_server_fake_mode_roundtrip():
    port = _get_free_port()
    server = AeroWebSocketServer(host="127.0.0.1", port=port, use_fake_servo=True)
    thread, get_loop = _start_server_in_thread(server)
    time.sleep(0.2)

    client = AeroWebSocketClient("127.0.0.1", port)
    assert client.connect(timeout=5.0) is True

    assert client.set_joint("index_proximal", 45.0, duration_ms=500) is True
    time.sleep(0.2)

    states = client.get_states(timeout=2.0)
    assert states is not None
    state_map = {state.joint_id: state.angle for state in states}
    assert state_map["index_proximal"] == 45.0

    assert client.homing() is True
    time.sleep(0.2)

    states_after_homing = client.get_states(timeout=2.0)
    assert states_after_homing is not None
    state_map_after_homing = {state.joint_id: state.angle for state in states_after_homing}
    assert state_map_after_homing["index_proximal"] == 0.0

    client.disconnect()

    loop = get_loop()
    if loop is not None:
        loop.call_soon_threadsafe(server.stop)
    thread.join(timeout=3.0)
    assert thread.is_alive() is False
