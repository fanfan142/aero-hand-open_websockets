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

import json
import logging
import time
import threading
from typing import List, Dict, Optional, Callable
from dataclasses import dataclass

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
        self._recv_thread: Optional[threading.Thread] = None
        self._running = False
        self._callbacks: Dict[str, Callable] = {}

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

        try:
            # websockets.connect 返回协程，需要同步方式运行
            import asyncio
            self.websocket = asyncio.run(websockets.connect(
                self.uri,
                open_timeout=timeout,
                close_timeout=1.0,
            ))
            self._connected = True
            self._running = True
            logger.info(f"Connected to {self.uri}")

            # 启动接收线程
            self._recv_thread = threading.Thread(target=self._recv_loop, daemon=True)
            self._recv_thread.start()

            return True
        except Exception as e:
            logger.error(f"Failed to connect: {e}")
            self._connected = False
            return False

    def disconnect(self):
        """断开连接"""
        self._running = False
        if self.websocket:
            try:
                self.websocket.close()
            except Exception:
                pass
            self.websocket = None
        self._connected = False
        logger.info("Disconnected")

    def is_connected(self) -> bool:
        """检查是否已连接"""
        return self._connected

    def _recv_loop(self):
        """接收消息循环（在独立线程中运行）"""
        while self._running and self.websocket:
            try:
                # 阻塞接收，直到收到消息或连接断开
                message = self.websocket.recv()
                if message:
                    self._handle_message(message)
            except Exception:
                # 连接关闭或出错，退出循环
                break

    def _handle_message(self, message: str):
        """处理接收到的消息"""
        try:
            obj = json.loads(message)
            msg_type = obj.get("type", "")
            callback = self._callbacks.get(msg_type)
            if callback:
                callback(obj)
        except json.JSONDecodeError:
            logger.warning(f"Invalid JSON: {message}")

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
        if not self._connected or not self.websocket:
            logger.error("Not connected")
            return False

        try:
            message = json.dumps(data, ensure_ascii=False)
            self.websocket.send(message)
            return True
        except Exception as e:
            logger.error(f"Send failed: {e}")
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
                "duration_ms": duration_ms
            }
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
                "duration_ms": duration_ms
            }
        }
        return self._send(command)

    def get_states(self) -> Optional[List[JointState]]:
        """
        获取所有关节当前状态

        Returns:
            关节状态列表，失败返回None
        """
        command = {
            "type": "get_states",
            "timestamp": int(time.time() * 1000)
        }

        if not self._send(command):
            return None

        # 等待响应（简化版，实际应该用异步回调）
        # 这里返回None，让调用者使用回调方式获取
        return None

    def homing(self) -> bool:
        """
        发送归零指令

        Returns:
            是否发送成功
        """
        command = {
            "type": "homing",
            "timestamp": int(time.time() * 1000)
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

    # 测试单关节控制
    client.set_joint("index_proximal", 45.0, duration_ms=500)
    time.sleep(1)

    # 测试多关节控制
    client.set_multi_joints([
        {"joint_id": "index_proximal", "angle": 30.0},
        {"joint_id": "index_middle", "angle": 20.0},
        {"joint_id": "index_distal", "angle": 10.0}
    ], duration_ms=500)
    time.sleep(1)

    # 测试归零
    client.homing()
    time.sleep(1)

    client.disconnect()
    print("Test completed")


if __name__ == "__main__":
    main()
