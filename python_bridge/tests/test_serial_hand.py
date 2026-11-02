"""Packet fixtures based on the official SDK's <2B7H> and actuator model."""

import asyncio
import json
import struct
import threading
import time

import pytest

from aero_ws_python.protocol import JOINT_TO_SDK_INDEX, JointCommand, JointData, MultiJointCommand
from aero_ws_python.serial_hand import OfficialSerialHand
from aero_ws_python.server import AeroWebSocketServer


class MockSerial:
    def __init__(self, **kwargs):
        self.options = kwargs
        self.written = []
        self.incoming = bytearray()
        self.reset_count = 0
        self.is_open = True

    def reset_input_buffer(self):
        self.reset_count += 1

    def reset_output_buffer(self):
        pass

    def write(self, packet):
        self.written.append(packet)
        return len(packet)

    def flush(self):
        pass

    def read(self, n):
        if not self.incoming:
            return b""
        value = self.incoming[:1]
        del self.incoming[:1]
        return bytes(value)

    def close(self):
        self.is_open = False


class MockWebSocket:
    def __init__(self):
        self.sent = []

    async def send(self, message):
        self.sent.append(json.loads(message))


def _hand():
    options = {}
    def serial_factory(**kwargs):
        options.update(kwargs)
        return serial
    serial = MockSerial()
    hand = OfficialSerialHand("COM42", serial_factory=serial_factory)
    serial.options = options
    return hand, serial


def test_usb_packets_match_official_sdk_16_byte_layout_and_baudrate():
    from aero_open_sdk.joints_to_actuations import JointsToActuationsModel
    hand, serial = _hand()
    assert serial.options["baudrate"] == 921600
    positions = [30, 20, 40, 45, 50, 50, 50, 60, 60, 60, 70, 70, 70, 80, 80, 80]
    hand.set_joint_positions(positions)
    model = JointsToActuationsModel()
    limits = hand.constants
    expected = [int((value - low) / (high - low) * 65535) for value, low, high in zip(
        model.hand_actuations(positions), limits.actuation_lower_limits, limits.actuation_upper_limits)]
    assert serial.written[-1] == struct.pack("<2B7H", 0x11, 0, *expected)
    assert len(serial.written[-1]) == 16
    hand.close()
    assert not serial.is_open


def test_get_pos_split_reply_decodes_official_compact_positions():
    hand, serial = _hand()
    positions = [25, 15, 35, 35, 55, 55, 55, 65, 65, 65, 75, 75, 75, 85, 85, 85]
    hand.set_joint_positions(positions)
    values = struct.unpack("<2B7H", serial.written.pop())[2:]
    serial.incoming.extend(b"\xff" + struct.pack("<2B7H", 0x22, 0, *values))
    measured = hand.get_joint_positions_compact()
    assert serial.written[-1] == struct.pack("<2B7H", 0x22, 0, *([0] * 7))
    assert len(measured) == 7
    for got, expected in zip(measured, [25, 15, 35, 55, 65, 75, 85]):
        assert got == pytest.approx(expected, abs=0.01)


def test_homing_requires_matching_zero_ack():
    hand, serial = _hand()
    serial.incoming.extend(struct.pack("<2B7H", 1, 0, *([0] * 7)))
    assert hand.send_homing(timeout_s=0.1) is True
    assert serial.written[-1] == struct.pack("<2B7H", 1, 0, *([0] * 7))
    with pytest.raises(TimeoutError):
        hand.send_homing(timeout_s=0.02)
    serial.incoming.extend(struct.pack("<2B7H", 1, 0, 1, *([0] * 6)))
    with pytest.raises(ValueError, match="nonzero"):
        hand.send_homing(timeout_s=0.1)


@pytest.mark.asyncio
async def test_bridge_mock_serial_single_multi_state_and_homing(monkeypatch):
    hand, serial = _hand()
    server = AeroWebSocketServer(serial_port="COM42")
    server.serial = hand
    ws = MockWebSocket()
    # Initial state comes from the hand, not a fabricated zero pose.
    initial = [10, 20, 30, 30, 40, 40, 40, 50, 50, 50, 60, 60, 60, 70, 70, 70]
    hand.set_joint_positions(initial)
    raw = struct.unpack("<2B7H", serial.written.pop())[2:]
    serial.incoming.extend(struct.pack("<2B7H", 0x22, 0, *raw))
    await server._handle_joint_command(ws, JointCommand("index_middle", 33))
    assert ws.sent[-1]["success"] is True
    assert len(serial.written) == 2  # first GET_POS, then one CTRL_POS
    assert serial.written[0][0] == 0x22
    assert serial.written[1][0] == 0x11
    assert server._joint_positions[JOINT_TO_SDK_INDEX["thumb_cmc_flex"]] == pytest.approx(20, abs=0.01)
    assert server._joint_positions[JOINT_TO_SDK_INDEX["index_middle"]] == 33
    serial.written.clear()
    cmd = MultiJointCommand([JointData("thumb_rotation", 0), JointData("thumb_proximal", 32)])
    await server._handle_multi_joint_command(ws, cmd)
    assert ws.sent[-1]["data"]["joints_count"] == 2
    assert len(serial.written) == 1
    assert server._joint_positions[:2] == [50, 32]
    await server._handle_joint_command(ws, JointCommand("thumb_distal", 48))
    assert server._joint_positions[2:4] == [48, 48]
    serial.incoming.extend(struct.pack("<2B7H", 0x22, 0, *raw))
    await server._handle_get_states(ws)
    assert ws.sent[-1]["type"] == "states_response"
    states = {item["joint_id"]: item["angle"] for item in ws.sent[-1]["data"]["joints"]}
    assert states["thumb_rotation"] == pytest.approx(-24, abs=0.01)
    assert states["thumb_proximal"] == pytest.approx(20, abs=0.01)
    assert states["thumb_distal"] == pytest.approx(30, abs=0.01)
    assert states["index_proximal"] == pytest.approx(40, abs=0.01)
    assert states["index_middle"] == pytest.approx(40, abs=0.01)
    server.clients.add(ws)
    serial.incoming.extend(struct.pack("<2B7H", 1, 0, *([0] * 7)))
    await server._handle_homing(ws)
    await server._homing_task
    assert ws.sent[-2]["success"] is True
    assert server._joint_positions == [0] * 16


@pytest.mark.asyncio
async def test_bridge_fails_closed_on_serial_errors_and_missing_port():
    server = AeroWebSocketServer()
    with pytest.raises(ValueError, match="serial port"):
        server._init_serial()
    ws = MockWebSocket()
    server = AeroWebSocketServer(serial_port="COM42")
    hand, serial = _hand()
    server.serial = hand
    await server._handle_joint_command(ws, JointCommand("index_proximal", 25))
    assert ws.sent[-1]["success"] is False
    assert ws.sent[-1]["error"]["code"] == "SERIAL_ERROR"
    assert serial.written[0][0] == 0x22
    assert not any(packet[0] == 0x11 for packet in serial.written)


def test_sdk_range_and_nonfinite_rejected():
    for joint_id, angle in [("thumb_rotation", -31), ("thumb_rotation", 31),
                             ("thumb_proximal", 56), ("thumb_cmc_flex", 56),
                             ("index_proximal", float("nan"))]:
        assert JointCommand(joint_id, angle).validate()[0] is False


def test_fake_mode_explicit_flag_preserves_legacy_joint_names():
    server = AeroWebSocketServer(use_fake_servo=True)
    assert server.serial_baudrate == 921600
    assert len(server._fake_joint_states) == 16
    assert JOINT_TO_SDK_INDEX["thumb_rotation"] == 0
    assert JOINT_TO_SDK_INDEX["thumb_proximal"] == 1
    assert JOINT_TO_SDK_INDEX["thumb_distal"] == 2
    assert JOINT_TO_SDK_INDEX["thumb_cmc_flex"] == 1


@pytest.mark.asyncio
async def test_multi_command_error_never_commits_partial_update():
    hand, serial = _hand()
    server = AeroWebSocketServer(serial_port="COM42")
    server.serial = hand
    server._positions_initialized = True
    original = server._joint_positions.copy()
    serial.write = lambda packet: 0
    ws = MockWebSocket()
    await server._handle_multi_joint_command(ws, MultiJointCommand(
        [JointData("index_proximal", 40), JointData("middle_proximal", 40)]
    ))
    assert ws.sent[-1]["success"] is False
    assert server._joint_positions == original


@pytest.mark.asyncio
async def test_homing_ack_error_does_not_report_success():
    hand, serial = _hand()
    server = AeroWebSocketServer(serial_port="COM42")
    server.serial = hand
    ws = MockWebSocket()
    serial.incoming.extend(struct.pack("<2B7H", 1, 0, 1, *([0] * 6)))
    await server._handle_homing(ws)
    await server._homing_task
    assert len(ws.sent) == 1
    assert ws.sent[0]["error"]["code"] == "SERIAL_ERROR"
