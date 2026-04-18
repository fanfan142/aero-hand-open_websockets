"""
Aero WebSocket Client
WebSocket客户端实现

用法:
    from aero_ws_python import AeroWebSocketClient

    client = AeroWebSocketClient("192.168.1.100", 8765)
    client.connect()

    # 控制单关节
    client.set_joint("index_proximal", 45.0, duration_ms=500)

    # 控制多关节
    client.set_multi_joints([
        {"joint_id": "index_proximal", "angle": 45.0},
        {"joint_id": "index_middle", "angle": 30.0}
    ], duration_ms=500)

    # 获取状态
    states = client.get_states()

    # 归零
    client.homing()

    client.disconnect()
"""

import asyncio
import json
import logging
import threading
import time
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional

try:
    import websockets
except ImportError:
    websockets = None

logger = logging.getLogger(__name__)


@dataclass
class JointState:
    """关节状态"""
    joint_id: str
    angle: float
    load: float = 0.0


class AeroWebSocketClient:
    """
    Aero Hand WebSocket 客户端

    用于向上位机或ESP32的WebSocket服务端发送控制指令
    """

    def __init__(self, host: str, port: int = 8765):
        """
        初始化客户端

        Args:
            host: 服务端地址
            port: 服务端端口
        """
        self.host = host
        self.port = port
        self.uri = f"ws://{host}:{port}"
        self.websocket = None
        self._connected = False
        self._callbacks: Dict[str, Callable] = {}
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._thread: Optional[threading.Thread] = None
        self._connected_event = threading.Event()
        self._ready_event = threading.Event()
        self._loop_started_event = threading.Event()
        self._stop_requested = False
        self._state_response_event = threading.Event()
        self._latest_states: Optional[List[JointState]] = None

    def connect(self, timeout: float = 10.0) -> bool:
        """
        连接到WebSocket服务端

        Args:
            timeout: 连接超时时间(秒)

        Returns:
            是否连接成功
        """
        if self._connected:
            logger.warning("Already connected")
            return True

        if websockets is None:
            logger.error("websockets library not installed. Run: pip install websockets")
            return False

        self._connected_event.clear()
        self._ready_event.clear()
        self._loop_started_event.clear()
        self._stop_requested = False
        self.websocket = None
        self._thread = threading.Thread(target=self._run_loop, args=(timeout,), daemon=True)
        self._thread.start()

        if not self._loop_started_event.wait(timeout=timeout):
            logger.error("Failed to start event loop thread")
            self.disconnect()
            return False

        if not self._ready_event.wait(timeout=timeout):
            logger.error("Connection timed out")
            self.disconnect()
            return False

        if not self._connected_event.is_set():
            logger.error("Failed to connect to %s", self.uri)
            self.disconnect()
            return False

        logger.info("Connected to %s", self.uri)
        return True

    def disconnect(self):
        """断开连接"""
        self._stop_requested = True
        self._connected = False
        self._connected_event.clear()
        self._ready_event.set()

        loop = self._loop
        websocket = self.websocket
        if loop and not loop.is_closed() and websocket is not None:
            future = asyncio.run_coroutine_threadsafe(websocket.close(), loop)
            try:
                future.result(timeout=2.0)
            except Exception:
                pass

        if self._thread and self._thread.is_alive() and self._thread is not threading.current_thread():
            self._thread.join(timeout=2.0)

        self.websocket = None
        self._loop = None
        self._thread = None
        logger.info("Disconnected")

    def is_connected(self) -> bool:
        """检查是否已连接"""
        return self._connected

    def _run_loop(self, timeout: float):
        """在线程中运行 asyncio 事件循环"""
        loop = asyncio.new_event_loop()
        self._loop = loop
        asyncio.set_event_loop(loop)
        self._loop_started_event.set()

        try:
            loop.run_until_complete(self._connection_main(timeout))
        except Exception as e:
            logger.error("Client loop error: %s", e)
        finally:
            pending = asyncio.all_tasks(loop)
            for task in pending:
                task.cancel()
            if pending:
                loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            loop.close()
            self._loop = None
            self.websocket = None
            self._connected = False
            self._connected_event.clear()
            self._ready_event.set()

    async def _connection_main(self, timeout: float):
        """建立连接并处理收发循环"""
        try:
            async with websockets.connect(
                self.uri,
                open_timeout=timeout,
                close_timeout=1.0,
            ) as websocket:
                self.websocket = websocket
                self._connected = True
                self._connected_event.set()
                self._ready_event.set()
                await self._recv_loop(websocket)
        except Exception as e:
            if not self._stop_requested:
                logger.error("Failed to connect: %s", e)
            self._connected = False
            self._connected_event.clear()
            self._ready_event.set()
        finally:
            self.websocket = None
            self._connected = False
            self._connected_event.clear()

    async def _recv_loop(self, websocket):
        """接收消息循环"""
        try:
            async for message in websocket:
                self._handle_message(message)
        except Exception as e:
            if not self._stop_requested:
                logger.error("Receive loop stopped: %s", e)

    def _handle_message(self, message: str):
        """处理接收到的消息"""
        try:
            obj = json.loads(message)
            msg_type = obj.get("type", "")

            if msg_type == "states_response":
                joints = obj.get("data", {}).get("joints", [])
                if isinstance(joints, list):
                    self._latest_states = [
                        JointState(
                            joint_id=str(joint.get("joint_id", "")),
                            angle=float(joint.get("angle", 0.0)),
                            load=float(joint.get("load", 0.0)),
                        )
                        for joint in joints
                        if isinstance(joint, dict)
                    ]
                    self._state_response_event.set()

            callback = self._callbacks.get(msg_type)
            if callback:
                callback(obj)
        except json.JSONDecodeError:
            logger.warning("Invalid JSON: %s", message)

    def on(self, event_type: str, callback: Callable):
        """
        注册消息回调

        Args:
            event_type: 消息类型，如 "states_response", "response"
            callback: 回调函数，接收解析后的dict参数
        """
        self._callbacks[event_type] = callback

    def _send(self, data: dict) -> bool:
        """发送JSON消息"""
        if not self._connected or not self.websocket or not self._loop:
            logger.error("Not connected")
            return False

        try:
            message = json.dumps(data, ensure_ascii=False)
            future = asyncio.run_coroutine_threadsafe(self.websocket.send(message), self._loop)
            future.result(timeout=5.0)
            return True
        except Exception as e:
            logger.error("Send failed: %s", e)
            return False

    def set_joint(self, joint_id: str, angle: float, duration_ms: int = 500) -> bool:
        """
        控制单关节

        Args:
            joint_id: 关节ID，如 "index_proximal"
            angle: 目标角度
            duration_ms: 动作持续时间(毫秒)

        Returns:
            是否发送成功
        """
        command = {
            "type": "joint_control",
            "timestamp": int(time.time() * 1000),
            "data": {
                "joint_id": joint_id,
                "angle": angle,
                "duration_ms": duration_ms,
            },
        }
        return self._send(command)

    def set_multi_joints(self, joints: List[Dict], duration_ms: int = 500) -> bool:
        """
        控制多关节同步

        Args:
            joints: 关节列表，如 [{"joint_id": "index_proximal", "angle": 45.0}]
            duration_ms: 动作持续时间(毫秒)

        Returns:
            是否发送成功
        """
        command = {
            "type": "multi_joint_control",
            "timestamp": int(time.time() * 1000),
            "data": {
                "joints": joints,
                "duration_ms": duration_ms,
            },
        }
        return self._send(command)

    def get_states(self, timeout: float = 2.0) -> Optional[List[JointState]]:
        """
        获取所有关节当前状态

        Args:
            timeout: 等待状态响应的超时时间(秒)

        Returns:
            关节状态列表，失败或超时返回None
        """
        self._latest_states = None
        self._state_response_event.clear()

        command = {
            "type": "get_states",
            "timestamp": int(time.time() * 1000),
        }

        if not self._send(command):
            return None

        if not self._state_response_event.wait(timeout=timeout):
            logger.error("Timed out waiting for states response")
            return None

        return self._latest_states

    def homing(self) -> bool:
        """
        发送归零指令

        Returns:
            是否发送成功
        """
        command = {
            "type": "homing",
            "timestamp": int(time.time() * 1000),
        }
        return self._send(command)

    def send_raw(self, json_str: str) -> bool:
        """
        发送原始JSON字符串

        Args:
            json_str: JSON格式字符串

        Returns:
            是否发送成功
        """
        return self._send(json.loads(json_str))


def main():
    """测试用主函数"""
    import argparse

    parser = argparse.ArgumentParser(description="Aero Hand WebSocket Client Test")
    parser.add_argument("--host", default="localhost", help="Server host")
    parser.add_argument("--port", type=int, default=8765, help="Server port")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO)

    client = AeroWebSocketClient(args.host, args.port)

    if not client.connect():
        print("Failed to connect")
        return

    print("Connected! Sending test commands...")

    client.set_joint("index_proximal", 45.0, duration_ms=500)
    time.sleep(1)

    client.set_multi_joints([
        {"joint_id": "index_proximal", "angle": 30.0},
        {"joint_id": "index_middle", "angle": 20.0},
        {"joint_id": "index_distal", "angle": 10.0},
    ], duration_ms=500)
    time.sleep(1)

    client.homing()
    time.sleep(1)

    client.disconnect()
    print("Test completed")


if __name__ == "__main__":
    main()
