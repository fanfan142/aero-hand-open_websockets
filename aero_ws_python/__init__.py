"""
Aero WebSocket Python SDK
Aero Hand Open 上位机通信Python包

Usage:
    from aero_ws_python import AeroWebSocketClient, AeroWebSocketServer

    # 作为服务端
    server = AeroWebSocketServer(port=8765)
    server.start()

    # 作为客户端
    client = AeroWebSocketClient("192.168.1.100", 8765)
    client.connect()
"""

__version__ = "1.0.0"
__author__ = "Aero Hand Open"

from .protocol import (
    JointCommand,
    MultiJointCommand,
    CommandType,
    JointState,
    parse_command,
    build_response,
    build_states_response,
    build_error,
)
from .client import AeroWebSocketClient
from .server import AeroWebSocketServer

__all__ = [
    "AeroWebSocketClient",
    "AeroWebSocketServer",
    "JointCommand",
    "MultiJointCommand",
    "CommandType",
    "JointState",
    "parse_command",
    "build_response",
    "build_states_response",
    "build_error",
]
