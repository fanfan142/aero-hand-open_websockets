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
    JOINT_TO_SDK_INDEX,
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
        serial_baudrate: int = 921600,
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
        self.serial = None  # OfficialSerialHand transport
        self._joint_positions = [0.0] * 16
        self._positions_initialized = False
        self._command_lock = asyncio.Lock()
        self._homing_task = None
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
        # A configured physical port must fail closed, never silently simulate a hand.
        if self.serial_port is None:
            raise ValueError("Specify a serial port, or explicitly enable --fake")
        from .serial_hand import OfficialSerialHand

        self.serial = OfficialSerialHand(self.serial_port, self.serial_baudrate)
        self._positions_initialized = False
        logger.info("Aero Hand serial port %s opened", self.serial_port)

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

        if self._homing_task is not None and not self._homing_task.done():
            await websocket.send(build_error(ErrorCode.INVALID_COMMAND, "Homing in progress"))
            return
        if self.use_fake_servo:
            self._fake_joint_states[cmd.joint_id] = cmd.angle
        else:
            async with self._command_lock:
                if not await self._write_positions({cmd.joint_id: cmd.angle}):
                    await websocket.send(build_error(ErrorCode.SERIAL_ERROR, "Failed to send joint positions"))
                    return
        await websocket.send(build_response(True, CommandType.JOINT_CONTROL.value))

    async def _handle_multi_joint_command(self, websocket: Any, cmd: MultiJointCommand):
        """处理多关节同步控制指令"""
        is_valid, err, err_code = cmd.validate()
        if not is_valid:
            await websocket.send(build_error(err_code or ErrorCode.INVALID_COMMAND, err))
            return

        if self._homing_task is not None and not self._homing_task.done():
            await websocket.send(build_error(ErrorCode.INVALID_COMMAND, "Homing in progress"))
            return
        updates = {joint.joint_id: joint.angle for joint in cmd.joints}
        if self.use_fake_servo:
            self._fake_joint_states.update(updates)
        else:
            async with self._command_lock:
                if not await self._write_positions(updates):
                    await websocket.send(build_error(ErrorCode.SERIAL_ERROR, "Failed to send joint positions"))
                    return
        await websocket.send(build_response(
            True, CommandType.MULTI_JOINT_CONTROL.value, {"joints_count": len(cmd.joints)})
        )

    async def _handle_get_states(self, websocket: Any):
        """处理获取状态指令"""
        if self._homing_task is not None and not self._homing_task.done():
            await websocket.send(build_error(ErrorCode.INVALID_COMMAND, "Homing in progress"))
            return
        if self.use_fake_servo:
            positions = [self._fake_joint_states[joint_id] for joint_id in JOINT_TO_SERVO_ID]
            states = [JointState(joint_id, angle) for joint_id, angle in zip(JOINT_TO_SERVO_ID, positions)]
        else:
            try:
                async with self._command_lock:
                    compact = await asyncio.to_thread(self.serial.get_joint_positions_compact)
                    # The official sensor reports compact tendon-based positions: MCP/PIP/DIP
                    # are estimated as a common angle (not three independent sensors).
                    positions = [compact[0], compact[1], compact[2], compact[2],
                                 *([angle for angle in compact[3:] for _ in range(3)])]
                    self._joint_positions = positions
                    self._positions_initialized = True
                states = [JointState(joint_id, positions[index] * 0.6 - 30.0
                                     if joint_id == 'thumb_rotation' else positions[index])
                          for joint_id, index in JOINT_TO_SDK_INDEX.items()]
            except (OSError, ValueError, TimeoutError) as exc:
                logger.error("Aero Hand state read failed: %s", exc)
                await websocket.send(build_error(ErrorCode.SERIAL_ERROR, "Failed to read hand positions"))
                return
        await websocket.send(build_states_response(states))

    async def _handle_homing(self, websocket: Any):
        """The SDK homing opcode completes only after an ACK (up to 175 seconds)."""
        if self._homing_task is not None and not self._homing_task.done():
            await websocket.send(build_error(ErrorCode.INVALID_COMMAND, "Homing already in progress"))
            return

        async def run_homing():
            try:
                if self.use_fake_servo:
                    self._fake_joint_states.update(dict.fromkeys(self._fake_joint_states, 0.0))
                else:
                    async with self._command_lock:
                        await asyncio.to_thread(self.serial.send_homing)
                        self._joint_positions = [0.0] * 16
                        self._positions_initialized = True
                await websocket.send(build_response(True, CommandType.HOMING.value))
                states = [JointState(joint_id, 0.0) for joint_id in JOINT_TO_SERVO_ID]
                response = build_states_response(states)
                for client in list(self.clients):
                    try:
                        await client.send(response)
                    except Exception:
                        pass
            except (OSError, ValueError, TimeoutError) as exc:
                logger.error("Homing failed: %s", exc)
                try:
                    await websocket.send(build_error(ErrorCode.SERIAL_ERROR, "Homing failed or timed out"))
                except Exception:
                    pass

        self._homing_task = asyncio.create_task(run_homing())
        await asyncio.sleep(0)  # Schedule before a subsequent command can overtake homing.

    async def _write_positions(self, updates: Dict[str, float]) -> bool:
        """Send all 16 joints in a single official CTRL_POS frame.

        On first use, obtain the official compact joint estimate before
        applying a partial command. This avoids moving the rest of the hand to
        zero when the bridge attaches to a hand already in motion.
        """
        if not self._positions_initialized:
            try:
                compact = await asyncio.to_thread(self.serial.get_joint_positions_compact)
                self._joint_positions = [compact[0], compact[1], compact[2], compact[2],
                                         *([angle for angle in compact[3:] for _ in range(3)])]
                self._positions_initialized = True
            except (OSError, ValueError, TimeoutError) as exc:
                logger.error("Cannot initialize hand position: %s", exc)
                return False
        positions = self._joint_positions.copy()
        for joint_id, angle in updates.items():
            index = JOINT_TO_SDK_INDEX[joint_id]
            positions[index] = ((angle + 30.0) * (100.0 / 60.0)
                                if joint_id == 'thumb_rotation' else angle)
            if joint_id == 'thumb_distal':
                positions[3] = angle
        try:
            await asyncio.to_thread(self.serial.set_joint_positions, positions)
        except (OSError, ValueError, TimeoutError) as exc:
            logger.error("Serial write error: %s", exc)
            return False
        self._joint_positions = positions
        self._positions_initialized = True
        return True

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

        if self._homing_task is not None:
            self._homing_task.cancel()
            try:
                await self._homing_task
            except asyncio.CancelledError:
                pass
            self._homing_task = None
        if self.serial is not None:
            await asyncio.to_thread(self.serial.close)
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
    parser.add_argument("--baudrate", type=int, default=921600, help="Serial baudrate")
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
