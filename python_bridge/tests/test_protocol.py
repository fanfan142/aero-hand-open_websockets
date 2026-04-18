"""Tests for aero_ws_python protocol module."""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from aero_ws_python.protocol import (  # noqa: E402
    CommandMessage,
    JointCommand,
    JointData,
    MultiJointCommand,
    ErrorCode,
    build_response,
    parse_command,
)


def test_joint_command_creation():
    """Test creating a single joint command."""
    cmd = JointCommand(
        joint_id="index_proximal",
        angle=45.0,
        duration_ms=500,
    )
    assert cmd.joint_id == "index_proximal"
    assert cmd.angle == 45.0
    assert cmd.duration_ms == 500


def test_joint_command_validation():
    """Test joint command validation."""
    cmd = JointCommand(joint_id="index_proximal", angle=45.0)
    is_valid, error, code = cmd.validate()
    assert is_valid is True
    assert error is None
    assert code is None

    cmd = JointCommand(joint_id="invalid_joint", angle=45.0)
    is_valid, error, code = cmd.validate()
    assert is_valid is False
    assert "Invalid joint_id" in error
    assert code == ErrorCode.INVALID_JOINT_ID


def test_multi_joint_command_creation():
    """Test creating a multi-joint command."""
    joints = [
        JointData(joint_id="index_proximal", angle=45.0),
        JointData(joint_id="index_middle", angle=30.0),
    ]
    cmd = MultiJointCommand(joints=joints, duration_ms=500)
    assert len(cmd.joints) == 2
    assert cmd.duration_ms == 500


def test_command_message_creation():
    """Test creating a command message."""
    msg = CommandMessage(
        type="get_states",
        timestamp=1234567890,
    )
    assert msg.type == "get_states"
    assert msg.timestamp == 1234567890


def test_parse_joint_control_command():
    """Test parsing a single joint control command."""
    json_str = '''
    {
        "type": "joint_control",
        "timestamp": 1234567890,
        "data": {
            "joint_id": "index_proximal",
            "angle": 45.0,
            "duration_ms": 500
        }
    }
    '''
    cmd, error = parse_command(json_str)
    assert error is None
    assert isinstance(cmd, JointCommand)
    assert cmd.joint_id == "index_proximal"
    assert cmd.angle == 45.0
    assert cmd.duration_ms == 500


def test_parse_multi_joint_command():
    """Test parsing a multi-joint control command."""
    json_str = '''
    {
        "type": "multi_joint_control",
        "timestamp": 1234567890,
        "data": {
            "joints": [
                {"joint_id": "index_proximal", "angle": 45.0},
                {"joint_id": "index_middle", "angle": 30.0}
            ],
            "duration_ms": 500
        }
    }
    '''
    cmd, error = parse_command(json_str)
    assert error is None
    assert isinstance(cmd, MultiJointCommand)
    assert len(cmd.joints) == 2
    assert cmd.duration_ms == 500


def test_parse_invalid_angle_type():
    """Reject non-numeric angle values."""
    json_str = '''
    {
        "type": "joint_control",
        "data": {
            "joint_id": "index_proximal",
            "angle": "abc"
        }
    }
    '''
    cmd, error = parse_command(json_str)
    assert cmd is None
    assert error == "Field 'angle' must be a number"


def test_parse_invalid_joint_list_item():
    """Reject malformed joint list entries."""
    json_str = '''
    {
        "type": "multi_joint_control",
        "data": {
            "joints": [1]
        }
    }
    '''
    cmd, error = parse_command(json_str)
    assert cmd is None
    assert error == "Joint item at index 0 must be an object"


def test_build_response():
    """Test building a success response."""
    response = build_response(True, "joint_control")
    assert '"success": true' in response
    assert '"command_type": "joint_control"' in response


def test_build_error_response_uses_invalid_command_code():
    """Test build_response error fallback code."""
    response = build_response(False, "joint_control", error_msg="bad request")
    assert '"success": false' in response
    assert f'"code": "{ErrorCode.INVALID_COMMAND}"' in response
