"""
Aero WebSocket Protocol Definition
通信协议定义

定义了所有JSON消息格式、关节映射和验证逻辑
"""

import json
import time
from dataclasses import asdict, dataclass
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple


class CommandType(Enum):
    """指令类型枚举"""
    JOINT_CONTROL = "joint_control"
    MULTI_JOINT_CONTROL = "multi_joint_control"
    GET_STATES = "get_states"
    HOMING = "homing"
    RESPONSE = "response"
    STATES_RESPONSE = "states_response"


JOINT_TO_SERVO_ID = {
    "thumb_proximal": 0,
    "thumb_distal": 1,
    "index_proximal": 2,
    "index_middle": 3,
    "index_distal": 4,
    "middle_proximal": 5,
    "middle_middle": 6,
    "middle_distal": 7,
    "ring_proximal": 8,
    "ring_middle": 9,
    "ring_distal": 10,
    "pinky_proximal": 11,
    "pinky_middle": 12,
    "pinky_distal": 13,
    "thumb_rotation": 14,
}

JOINT_ANGLE_LIMITS = {
    "proximal": (0, 90),
    "middle": (0, 90),
    "distal": (0, 90),
    "rotation": (-30, 30),
}

VALID_JOINT_IDS = set(JOINT_TO_SERVO_ID.keys())


class ErrorCode:
    """协议错误码常量"""
    INVALID_ANGLE = "INVALID_ANGLE"
    INVALID_JOINT_ID = "INVALID_JOINT_ID"
    INVALID_COMMAND = "INVALID_COMMAND"
    PARSE_ERROR = "PARSE_ERROR"
    SERVO_ERROR = "SERVO_ERROR"
    SERIAL_ERROR = "SERIAL_ERROR"
    TIMEOUT = "TIMEOUT"


def get_joint_type(joint_id: str) -> str:
    """从关节ID中提取关节类型"""
    if "rotation" in joint_id:
        return "rotation"
    for joint_type in ["proximal", "middle", "distal"]:
        if joint_type in joint_id:
            return joint_type
    return "proximal"


def get_angle_limits(joint_id: str) -> tuple:
    """获取关节的角度限制"""
    joint_type = get_joint_type(joint_id)
    return JOINT_ANGLE_LIMITS.get(joint_type, (0, 90))


def _coerce_number(value: Any, field_name: str) -> Tuple[Optional[float], Optional[str]]:
    """将输入安全转换为 float"""
    if isinstance(value, bool):
        return None, f"Field '{field_name}' must be a number"
    try:
        return float(value), None
    except (TypeError, ValueError):
        return None, f"Field '{field_name}' must be a number"


def _coerce_int(value: Any, field_name: str) -> Tuple[Optional[int], Optional[str]]:
    """将输入安全转换为 int"""
    if isinstance(value, bool):
        return None, f"Field '{field_name}' must be an integer"
    try:
        return int(value), None
    except (TypeError, ValueError):
        return None, f"Field '{field_name}' must be an integer"


@dataclass
class JointCommand:
    """单关节控制指令"""
    joint_id: str
    angle: float
    duration_ms: int = 500

    def validate(self) -> tuple:
        """验证指令合法性，返回(is_valid, error_message, error_code)"""
        if self.joint_id not in VALID_JOINT_IDS:
            return False, f"Invalid joint_id: {self.joint_id}", ErrorCode.INVALID_JOINT_ID

        min_angle, max_angle = get_angle_limits(self.joint_id)
        if not (min_angle <= self.angle <= max_angle):
            return False, f"Angle {self.angle} out of range [{min_angle}, {max_angle}]", ErrorCode.INVALID_ANGLE

        if self.duration_ms < 0 or self.duration_ms > 5000:
            return False, f"Duration {self.duration_ms} out of range [0, 5000]", ErrorCode.INVALID_COMMAND

        return True, None, None

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class JointData:
    """关节数据"""
    joint_id: str
    angle: float
    load: float = 0.0


@dataclass
class MultiJointCommand:
    """多关节同步控制指令"""
    joints: List[JointData]
    duration_ms: int = 500

    def validate(self) -> tuple:
        """验证指令合法性"""
        if not self.joints:
            return False, "No joints specified", ErrorCode.INVALID_COMMAND

        if self.duration_ms < 0 or self.duration_ms > 5000:
            return False, f"Duration {self.duration_ms} out of range [0, 5000]", ErrorCode.INVALID_COMMAND

        for joint in self.joints:
            is_valid, err, err_code = JointCommand(joint.joint_id, joint.angle, self.duration_ms).validate()
            if not is_valid:
                return False, err, err_code

        return True, None, None

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class JointState:
    """关节状态"""
    joint_id: str
    angle: float
    load: float = 0.0

    def to_dict(self) -> dict:
        return {
            "joint_id": self.joint_id,
            "angle": self.angle,
            "load": self.load,
        }


@dataclass
class CommandMessage:
    """通用指令消息"""
    type: str
    timestamp: int
    data: Optional[Dict[str, Any]] = None

    @classmethod
    def from_json(cls, json_str: str) -> "CommandMessage":
        """从JSON字符串解析"""
        obj = json.loads(json_str)
        return cls(
            type=obj.get("type", ""),
            timestamp=obj.get("timestamp", int(time.time() * 1000)),
            data=obj.get("data"),
        )

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)

    def to_dict(self) -> dict:
        result = {"type": self.type, "timestamp": self.timestamp}
        if self.data is not None:
            result["data"] = self.data
        return result


def parse_command(json_str: str) -> tuple:
    """
    解析JSON指令，返回 (command_obj, error)
    command_obj 可能是 JointCommand 或 MultiJointCommand 或 CommandMessage
    """
    try:
        obj = json.loads(json_str)
    except json.JSONDecodeError as e:
        return None, f"JSON parse error: {e}"

    if not isinstance(obj, dict):
        return None, "Command payload must be a JSON object"

    cmd_type = obj.get("type", "")
    timestamp = obj.get("timestamp", int(time.time() * 1000))
    data = obj.get("data")

    if not isinstance(cmd_type, str) or not cmd_type:
        return None, "Missing or invalid command type"

    if cmd_type == CommandType.JOINT_CONTROL.value:
        if not isinstance(data, dict):
            return None, "Missing data field"

        joint_id = data.get("joint_id")
        if not isinstance(joint_id, str) or not joint_id:
            return None, "Field 'joint_id' must be a non-empty string"

        angle, angle_error = _coerce_number(data.get("angle"), "angle")
        if angle_error:
            return None, angle_error

        duration, duration_error = _coerce_int(data.get("duration_ms", 500), "duration_ms")
        if duration_error:
            return None, duration_error

        return JointCommand(joint_id, angle, duration), None

    if cmd_type == CommandType.MULTI_JOINT_CONTROL.value:
        if not isinstance(data, dict):
            return None, "Missing joints data"

        joints_data = data.get("joints")
        if not isinstance(joints_data, list):
            return None, "Field 'joints' must be a list"

        duration, duration_error = _coerce_int(data.get("duration_ms", 500), "duration_ms")
        if duration_error:
            return None, duration_error

        joints: List[JointData] = []
        for index, joint in enumerate(joints_data):
            if not isinstance(joint, dict):
                return None, f"Joint item at index {index} must be an object"

            joint_id = joint.get("joint_id")
            if not isinstance(joint_id, str) or not joint_id:
                return None, f"Joint item at index {index} has invalid joint_id"

            angle, angle_error = _coerce_number(joint.get("angle"), f"joints[{index}].angle")
            if angle_error:
                return None, angle_error

            joints.append(JointData(joint_id, angle))

        return MultiJointCommand(joints, duration), None

    if cmd_type == CommandType.GET_STATES.value:
        return CommandMessage(type=cmd_type, timestamp=timestamp), None

    if cmd_type == CommandType.HOMING.value:
        return CommandMessage(type=cmd_type, timestamp=timestamp), None

    return None, f"Unknown command type: {cmd_type}"


def build_response(success: bool, command_type: str, data: Dict = None, error_msg: str = None) -> str:
    """构建响应JSON"""
    if error_msg:
        response = {
            "type": "response",
            "success": False,
            "timestamp": int(time.time() * 1000),
            "error": {
                "code": ErrorCode.INVALID_COMMAND,
                "message": error_msg,
            },
        }
    else:
        response = {
            "type": "response",
            "success": success,
            "timestamp": int(time.time() * 1000),
            "data": {
                "command_type": command_type,
                "executed": success,
            },
        }
        if data:
            response["data"].update(data)

    return json.dumps(response, ensure_ascii=False)


def build_states_response(states: List[JointState]) -> str:
    """构建状态查询响应"""
    response = {
        "type": "states_response",
        "success": True,
        "timestamp": int(time.time() * 1000),
        "data": {
            "joints": [s.to_dict() for s in states],
        },
    }
    return json.dumps(response, ensure_ascii=False)


def build_error(code: str, message: str) -> str:
    """构建错误响应"""
    response = {
        "type": "response",
        "success": False,
        "timestamp": int(time.time() * 1000),
        "error": {
            "code": code,
            "message": message,
        },
    }
    return json.dumps(response, ensure_ascii=False)
