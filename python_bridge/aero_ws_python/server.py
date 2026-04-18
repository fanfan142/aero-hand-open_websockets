"""
Aero WebSocket Server
WebSocket服务端实现

用法:
    from aero_ws_python import AeroWebSocketServer

    server = AeroWebSocketServer(port=8765, serial_port="COM3")
    server.start()
"""

import asyncio
import logging
from typing import Any, Dict, Optional, Set

import websockets

from .protocol import (
    CommandMessage,
    CommandType,
    ErrorCode,
    JointCommand,
    JointState,
    MultiJointCommand,
    JOINT_TO_SERVO_ID,
    build_error,
    build_response,
    build_states_response,
    parse_command,
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

        self.clients: Set[Any] = set()
        self.server = None
        self.serial = None
        self._running = False
        self._stop_event = asyncio.Event()

        self._fake_joint_states: Dict[str, float] = {
            joint_id: 0.0 for joint_id in JOINT_TO_SERVO_ID.keys()
        }

    async def start(self):
        """启动WebSocket服务"""
        if self._running:
            logger.warning("Server already running")
            return

        if not self.use_fake_servo:
            self._init_serial()

        self._running = True
        self._stop_event = asyncio.Event()
        logger.info("Starting AeroWebSocketServer on %s:%s", self.host, self.port)

        try:
            self.server = await websockets.serve(self._handle_client, self.host, self.port)
            logger.info("Server started, waiting for connections...")
            await self._stop_event.wait()
        except asyncio.CancelledError:
            logger.info("Server cancelled")
            raise
        finally:
            await self._shutdown()

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
            logger.info("Serial port %s opened", self.serial_port)
        except Exception as e:
            logger.error("Failed to open serial port: %s", e)
            logger.warning("Falling back to fake servo mode")
            self.use_fake_servo = True

    async def _handle_client(self, websocket):
        """处理客户端连接"""
        self.clients.add(websocket)
        client_addr = websocket.remote_address
        logger.info("Client connected: %s", client_addr)

        try:
            async for message in websocket:
                await self._process_message(websocket, message)
        except websockets.exceptions.ConnectionClosed:
            logger.info("Client disconnected: %s", client_addr)
        except Exception as e:
            logger.error("Error handling client %s: %s", client_addr, e)
        finally:
            self.clients.discard(websocket)

    async def _process_message(self, websocket: Any, message: str):
        """处理接收到的消息"""
        logger.debug("Received: %s", message)

        cmd, error = parse_command(message)
        if error:
            await websocket.send(build_error(ErrorCode.PARSE_ERROR, error))
            return

        if isinstance(cmd, JointCommand):
            await self._handle_joint_command(websocket, cmd)
            return

        if isinstance(cmd, MultiJointCommand):
            await self._handle_multi_joint_command(websocket, cmd)
            return

        if isinstance(cmd, CommandMessage):
            if cmd.type == CommandType.GET_STATES.value:
                await self._handle_get_states(websocket)
            elif cmd.type == CommandType.HOMING.value:
                await self._handle_homing(websocket)
            else:
                await websocket.send(build_error(ErrorCode.INVALID_COMMAND, f"Unsupported command type: {cmd.type}"))

    async def _handle_joint_command(self, websocket: Any, cmd: JointCommand):
        """处理单关节控制指令"""
        is_valid, err, err_code = cmd.validate()
        if not is_valid:
            await websocket.send(build_error(err_code or ErrorCode.INVALID_COMMAND, err))
            return

        success = self._send_to_servo(cmd.joint_id, cmd.angle, cmd.duration_ms)
        if success:
            if self.use_fake_servo:
                self._fake_joint_states[cmd.joint_id] = cmd.angle
            response = build_response(True, CommandType.JOINT_CONTROL.value)
        else:
            response = build_error(ErrorCode.SERVO_ERROR, "Failed to send command to servo")

        await websocket.send(response)

    async def _handle_multi_joint_command(self, websocket: Any, cmd: MultiJointCommand):
        """处理多关节同步控制指令"""
        is_valid, err, err_code = cmd.validate()
        if not is_valid:
            await websocket.send(build_error(err_code or ErrorCode.INVALID_COMMAND, err))
            return

        sent_count = 0
        for joint in cmd.joints:
            if not self._send_to_servo(joint.joint_id, joint.angle, cmd.duration_ms):
                await websocket.send(build_error(ErrorCode.SERVO_ERROR, f"Failed to send command for joint {joint.joint_id}"))
                return
            sent_count += 1
            if self.use_fake_servo:
                self._fake_joint_states[joint.joint_id] = joint.angle

        response = build_response(
            True,
            CommandType.MULTI_JOINT_CONTROL.value,
            {"joints_count": sent_count},
        )
        await websocket.send(response)

    async def _handle_get_states(self, websocket: Any):
        """处理获取状态指令"""
        states = []
        for joint_id in JOINT_TO_SERVO_ID.keys():
            if self.use_fake_servo:
                angle = self._fake_joint_states.get(joint_id, 0.0)
                load = 0.0
            else:
                angle = self._read_servo_angle(joint_id)
                load = 0.0

            states.append(JointState(joint_id, angle, load))

        await websocket.send(build_states_response(states))

    async def _handle_homing(self, websocket: Any):
        """处理归零指令"""
        # 归零是长时间操作，在后台执行，不阻塞客户端响应
        async def run_homing():
            try:
                if self.use_fake_servo:
                    for joint_id in self._fake_joint_states:
                        self._fake_joint_states[joint_id] = 0.0
                else:
                    # 分步执行，每个关节间隔发送，避免总线冲突
                    for joint_id in JOINT_TO_SERVO_ID.keys():
                        self._send_to_servo(joint_id, 0.0, 1000)
                        await asyncio.sleep(0.05)  # 50ms 间隔

                # 归零完成后广播状态更新
                if self.clients:
                    states = []
                    for joint_id in JOINT_TO_SERVO_ID.keys():
                        states.append(JointState(joint_id, 0.0, 0.0))
                    response = build_states_response(states)
                    for client in list(self.clients):
                        try:
                            await client.send(response)
                        except Exception:
                            pass
            except Exception as e:
                logger.error("Homing error: %s", e)

        # 立即响应客户端，归零在后台异步执行
        asyncio.create_task(run_homing())
        await websocket.send(build_response(True, CommandType.HOMING.value))

    def _send_to_servo(self, joint_id: str, angle: float, duration_ms: int) -> bool:
        """发送指令到舵机"""
        if self.use_fake_servo:
            return True

        servo_id = JOINT_TO_SERVO_ID.get(joint_id)
        if servo_id is None:
            return False

        try:
            angle_int = int(angle * 10)
            duration_int = max(0, min(int(duration_ms), 5000))
            data = bytes([
                0x55,
                0x01,
                servo_id,
                angle_int & 0xFF,
                (angle_int >> 8) & 0xFF,
                duration_int & 0xFF,
                (duration_int >> 8) & 0xFF,
                0x55 ^ 0x01 ^ servo_id ^ (angle_int & 0xFF) ^ ((angle_int >> 8) & 0xFF) ^ (duration_int & 0xFF) ^ ((duration_int >> 8) & 0xFF),
            ])
            self.serial.write(data)
            return True
        except Exception as e:
            logger.error("Serial write error: %s", e)
            return False

    def _read_servo_angle(self, joint_id: str) -> float:
        """读取舵机当前角度"""
        if self.use_fake_servo:
            return self._fake_joint_states.get(joint_id, 0.0)

        servo_id = JOINT_TO_SERVO_ID.get(joint_id)
        if servo_id is None:
            return 0.0

        try:
            # 发送读取位置指令 (0x55 0x02 + servo_id + 校验)
            data = bytes([
                0x55,
                0x02,
                servo_id,
                0x55 ^ 0x02 ^ servo_id,
            ])
            self.serial.write(data)

            # 读取响应 (7 bytes: 0x55 0x02 servo_id angle_low angle_high temp checksum)
            import time
            time.sleep(0.01)  # 等待舵机响应

            if self.serial.in_waiting >= 7:
                response = self.serial.read(7)
                if response[0] == 0x55 and response[1] == 0x02:
                    angle_raw = response[3] | (response[4] << 8)
                    # 舵机角度范围 0-1000 对应 0-90度
                    return round((angle_raw / 1000.0) * 90.0, 1)

            return self._fake_joint_states.get(joint_id, 0.0)
        except Exception as e:
            logger.error("Serial read error for joint %s: %s", joint_id, e)
            return self._fake_joint_states.get(joint_id, 0.0)

    def _send_homing_command(self):
        """发送归零指令"""
        for joint_id in JOINT_TO_SERVO_ID.keys():
            self._send_to_servo(joint_id, 0.0, 1000)

    async def _shutdown(self):
        """关闭服务与资源"""
        if self.server is not None:
            self.server.close()
            await self.server.wait_closed()
            self.server = None

        clients = list(self.clients)
        self.clients.clear()
        for client in clients:
            try:
                await client.close()
            except Exception:
                pass

        if self.serial and self.serial.is_open:
            self.serial.close()
        self.serial = None
        self._running = False
        logger.info("Server stopped")

    def stop(self):
        """停止服务"""
        self._running = False
        if not self._stop_event.is_set():
            self._stop_event.set()


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
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
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
