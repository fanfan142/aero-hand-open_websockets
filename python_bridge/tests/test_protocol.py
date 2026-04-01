"""Tests for aero_ws_python protocol module."""

import pytest
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from aero_ws_python.protocol import (
    JointCommand, JointData, MultiJointCommand,
    CommandMessage, parse_command, build_response
)


def test_joint_command_creation():
    """Test creating a single joint command."""
    cmd = JointCommand(
        joint_id="index_proximal",
        angle=45.0,
        duration_ms=500
    )
    assert cmd.joint_id == "index_proximal"
    assert cmd.angle == 45.0
    assert cmd.duration_ms == 500


def test_joint_command_validation():
    """Test joint command validation."""
    # Valid command
    cmd = JointCommand(joint_id="index_proximal", angle=45.0)
    is_valid, error = cmd.validate()
    assert is_valid is True

    # Invalid joint_id
    cmd = JointCommand(joint_id="invalid_joint", angle=45.0)
    is_valid, error = cmd.validate()
    assert is_valid is False
    assert "Invalid joint_id" in error


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
        timestamp=1234567890
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


def test_build_response():
    """Test building a response."""
    response = build_response(True, "joint_control")
    assert '"success": true' in response
    assert '"command_type": "joint_control"' in response
