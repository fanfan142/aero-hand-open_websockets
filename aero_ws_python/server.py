"""
Aero WebSocket Server
WebSocket服务端实现

用法:
    from aero_ws_python import AeroWebSocketServer

    server = AeroWebSocketServer(port=8765, serial_port="COM3")
    server.start()
"""

import asyncio
import json
import logging
import time
from typing import Dict, Set, Optional
import websockets
from websockets.server import WebSocketServerProtocol

from .protocol import (
    CommandType,
    JointCommand,
    MultiJointCommand,
    JointState,
    parse_command,
    build_response,
    build_states_response,
    build_error,
    JOINT_TO_SERVO_ID,
)

logger = logging.getLogger(__name__)


class AeroWebSocketServer:
    """
    Aero Hand WebSocket 服务端

    接收上位机指令，通过串口/USB转发给ESP32控制机械手
    """

    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = 8765,
        serial_port: Optional[str] = None,
        serial_baudrate: int = 115200,
        use_fake_servo: bool = False,
    ):
        """
        初始化服务端

        Args:
            host: 监听地址，0.0.0.0表示所有网络接口
            port: WebSocket监听端口
            serial_port: 串口设备名，如"COM3"或"/dev/ttyUSB0"
            serial_baudrate: 串口波特率
            use_fake_servo: 是否使用模拟舵机（用于测试，无实际硬件时）
        """
        self.host = host
        self.port = port
        self.serial_port = serial_port
        self.serial_baudrate = serial_baudrate
        self.use_fake_servo = use_fake_servo

        self.clients: Set[WebSocketServerProtocol] = set()
        self.server = None
        self.serial = None
        self._running = False

        # 模拟舵机状态（用于测试）
        self._fake_joint_states: Dict[str, float] = {
            joint_id: 0.0 for joint_id in JOINT_TO_SERVO_ID.keys()
        }

    async def start(self):
        """启动WebSocket服务"""
        if self._running:
            logger.warning("Server already running")
            return

        # 初始化串口
        if not self.use_fake_servo:
            self._init_serial()

        self._running = True
        logger.info(f"Starting AeroWebSocketServer on {self.host}:{self.port}")

        try:
            async with websockets.serve(self._handle_client, self.host, self.port):
                logger.info(f"Server started, waiting for connections...")
                await asyncio.Future()  # 永远运行
        except asyncio.CancelledError:
            logger.info("Server cancelled")
        finally:
            self._running = False

    def _init_serial(self):
        """初始化串口连接"""
        if self.serial_port is None:
            logger.warning("No serial port specified, using fake servo mode")
            self.use_fake_servo = True
            return

        try:
            import serial
            self.serial = serial.Serial(
                port=self.serial_port,
                baudrate=self.serial_baudrate,
                timeout=1.0,
            )
            logger.info(f"Serial port {self.serial_port} opened")
        except Exception as e:
            logger.error(f"Failed to open serial port: {e}")
            logger.warning("Falling back to fake servo mode")
            self.use_fake_servo = True

    async def _handle_client(self, websocket: WebSocketServerProtocol, path: str):
        """处理客户端连接"""
        self.clients.add(websocket)
        client_addr = websocket.remote_address
        logger.info(f"Client connected: {client_addr}")

        try:
            async for message in websocket:
                await self._process_message(websocket, message)
        except websockets.exceptions.ConnectionClosed:
            logger.info(f"Client disconnected: {client_addr}")
        except Exception as e:
            logger.error(f"Error handling client {client_addr}: {e}")
        finally:
            self.clients.discard(websocket)

    async def _process_message(self, websocket: WebSocketServerProtocol, message: str):
        """处理接收到的消息"""
        logger.debug(f"Received: {message}")

        cmd, error = parse_command(message)
        if error:
            response = build_error("PARSE_ERROR", error)
            await websocket.send(response)
            return

        if isinstance(cmd, JointCommand):
            await self._handle_joint_command(websocket, cmd)
        elif isinstance(cmd, MultiJointCommand):
            await self._handle_multi_joint_command(websocket, cmd)
        elif hasattr(cmd, 'type'):
            # 处理CommandMessage类型（get_states, homing等）
            if cmd.type == CommandType.GET_STATES.value:
                await self._handle_get_states(websocket)
            elif cmd.type == CommandType.HOMING.value:
                await self._handle_homing(websocket)

    async def _handle_joint_command(self, websocket: WebSocketServerProtocol, cmd: JointCommand):
        """处理单关节控制指令"""
        is_valid, err = cmd.validate()
        if not is_valid:
            response = build_error("INVALID_ANGLE", err)
            await websocket.send(response)
            return

        # 发送到舵机
        success = self._send_to_servo(cmd.joint_id, cmd.angle, cmd.duration_ms)
        if success:
            # 更新模拟状态
            if self.use_fake_servo:
                self._fake_joint_states[cmd.joint_id] = cmd.angle
            response = build_response(True, CommandType.JOINT_CONTROL.value)
        else:
            response = build_error("SERVO_ERROR", "Failed to send command to servo")

        await websocket.send(response)

    async def _handle_multi_joint_command(self, websocket: WebSocketServerProtocol, cmd: MultiJointCommand):
        """处理多关节同步控制指令"""
        is_valid, err = cmd.validate()
        if not is_valid:
            response = build_error("INVALID_COMMAND", err)
            await websocket.send(response)
            return

        # 依次发送到舵机
        for joint in cmd.joints:
            self._send_to_servo(joint.joint_id, joint.angle, cmd.duration_ms)
            if self.use_fake_servo:
                self._fake_joint_states[joint.joint_id] = joint.angle

        response = build_response(True, CommandType.MULTI_JOINT_CONTROL.value, {
            "joints_count": len(cmd.joints)
        })
        await websocket.send(response)

    async def _handle_get_states(self, websocket: WebSocketServerProtocol):
        """处理获取状态指令"""
        states = []
        for joint_id in JOINT_TO_SERVO_ID.keys():
            if self.use_fake_servo:
                angle = self._fake_joint_states.get(joint_id, 0.0)
                load = 0.0
            else:
                angle = self._read_servo_angle(joint_id)
                load = 0.0  # 简化版，实际可读取负载值

            states.append(JointState(joint_id, angle, load))

        response = build_states_response(states)
        await websocket.send(response)

    async def _handle_homing(self, websocket: WebSocketServerProtocol):
        """处理归零指令"""
        if self.use_fake_servo:
            for joint_id in self._fake_joint_states:
                self._fake_joint_states[joint_id] = 0.0
        else:
            self._send_homing_command()

        response = build_response(True, CommandType.HOMING.value)
        await websocket.send(response)

    def _send_to_servo(self, joint_id: str, angle: float, duration_ms: int) -> bool:
        """发送指令到舵机"""
        if self.use_fake_servo:
            return True

        servo_id = JOINT_TO_SERVO_ID.get(joint_id)
        if servo_id is None:
            return False

        try:
            # 构建二进制指令
            # 协议: [0x55, 0x01, servo_id, angle_low, angle_high, checksum]
            angle_int = int(angle * 10)  # 角度放大10倍
            data = bytes([
                0x55,                   # 包头
                0x01,                   # 版本
                servo_id,               # 舵机ID
                angle_int & 0xFF,       # 角度低8位
                (angle_int >> 8) & 0xFF, # 角度高8位
                0x55 ^ 0x01 ^ servo_id ^ (angle_int & 0xFF) ^ ((angle_int >> 8) & 0xFF)  # 校验和
            ])
            self.serial.write(data)
            return True
        except Exception as e:
            logger.error(f"Serial write error: {e}")
            return False

    def _read_servo_angle(self, joint_id: str) -> float:
        """读取舵机当前角度"""
        return self._fake_joint_states.get(joint_id, 0.0)

    def _send_homing_command(self):
        """发送归零指令"""
        # 所有关节归零
        for joint_id in JOINT_TO_SERVO_ID.keys():
            self._send_to_servo(joint_id, 0.0, 1000)

    def stop(self):
        """停止服务"""
        self._running = False
        if self.server:
            self.server.close()
        if self.serial and self.serial.is_open:
            self.serial.close()
        logger.info("Server stopped")


async def main():
    """主函数 - 用于直接运行此文件测试"""
    import argparse

    parser = argparse.ArgumentParser(description="Aero Hand WebSocket Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind")
    parser.add_argument("--port", type=int, default=8765, help="Port to listen")
    parser.add_argument("--serial", default=None, help="Serial port")
    parser.add_argument("--baudrate", type=int, default=115200, help="Serial baudrate")
    parser.add_argument("--fake", action="store_true", help="Use fake servo mode")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )

    server = AeroWebSocketServer(
        host=args.host,
        port=args.port,
        serial_port=args.serial,
        serial_baudrate=args.baudrate,
        use_fake_servo=args.fake,
    )

    await server.start()


if __name__ == "__main__":
    asyncio.run(main())
