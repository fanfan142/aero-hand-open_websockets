"""USB transport for the official Aero Hand Open 16-byte serial protocol.

The upstream SDK supplies the joint limits and tendon/actuator kinematics. The
transport keeps its wire format but handles split serial reads and reports I/O
errors rather than treating an unacknowledged write as a confirmed movement.
"""

import math
import struct
import threading
import time


class OfficialSerialHand:
    CTRL_POS = 0x11
    GET_POS = 0x22
    HOMING = 0x01
    FRAME = struct.Struct("<2B7H")

    def __init__(self, port, baudrate=921600, serial_factory=None):
        import serial
        from aero_open_sdk.aero_hand_constants import AeroHandConstants
        from aero_open_sdk.actuations_to_joints import ActuationsToJointsModelCompact
        from aero_open_sdk.joints_to_actuations import JointsToActuationsModel

        self.constants = AeroHandConstants()
        self.to_actuations = JointsToActuationsModel()
        self.to_joints = ActuationsToJointsModelCompact()
        self._lock = threading.RLock()
        factory = serial_factory or serial.Serial
        self.ser = factory(port=port, baudrate=baudrate, timeout=0.01, write_timeout=0.01)
        try:
            self.ser.reset_input_buffer()
            self.ser.reset_output_buffer()
        except Exception:
            self.ser.close()
            raise

    def close(self):
        self.ser.close()

    def _send(self, opcode, words=None):
        values = [0] * 7 if words is None else words
        if len(values) != 7 or any(not 0 <= value <= 65535 for value in values):
            raise ValueError("Expected seven unsigned 16-bit actuator values")
        packet = self.FRAME.pack(opcode, 0, *values)
        if self.ser.write(packet) != len(packet):
            raise IOError("Incomplete Aero Hand serial write")
        self.ser.flush()

    def _receive(self, opcode, timeout):
        """Read an entire reply even when the serial driver splits it."""
        deadline = time.monotonic() + timeout
        pending = bytearray()
        while time.monotonic() < deadline:
            chunk = self.ser.read(1)
            if not chunk:
                time.sleep(0.001)
                continue
            pending.extend(chunk)
            while pending and pending[0] != opcode:
                del pending[0]
            if len(pending) >= 2 and pending[1] != 0:
                del pending[0]
                if pending and pending[0] != opcode:
                    pending.clear()
            if len(pending) == self.FRAME.size:
                return self.FRAME.unpack(bytes(pending))[2:]
        raise TimeoutError("Aero Hand reply 0x%02x timed out" % opcode)

    def set_joint_positions(self, positions):
        if len(positions) != 16 or not all(math.isfinite(value) for value in positions):
            raise ValueError("Expected 16 finite joint angles")
        with self._lock:
            positions = [max(low, min(value, high)) for value, low, high in zip(
                positions, self.constants.joint_lower_limits, self.constants.joint_upper_limits
            )]
            actuations = self.to_actuations.hand_actuations(positions)
            values = [int((value - low) / (high - low) * 65535) for value, low, high in zip(
                actuations, self.constants.actuation_lower_limits, self.constants.actuation_upper_limits
            )]
            self._send(self.CTRL_POS, values)

    def get_joint_positions_compact(self):
        with self._lock:
            self.ser.reset_input_buffer()
            self._send(self.GET_POS)
            raw = self._receive(self.GET_POS, 0.5)
            actuations = [low + value / 65535 * (high - low) for value, low, high in zip(
                raw, self.constants.actuation_lower_limits, self.constants.actuation_upper_limits
            )]
            radians = self.to_joints.hand_joints([math.radians(value) for value in actuations])
            return [math.degrees(value) for value in radians]


    def send_homing(self, timeout_s=175.0):
        with self._lock:
            self.ser.reset_input_buffer()
            self._send(self.HOMING)
            if any(self._receive(self.HOMING, timeout_s)):
                raise ValueError("Unexpected nonzero homing acknowledgment")
            return True
