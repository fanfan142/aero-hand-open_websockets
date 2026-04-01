# Aero WebSocket Python SDK

Aero Hand Open 的 Python WebSocket 通信 SDK，支持服务端和客户端模式。

## 安装

```bash
# 从源码安装
cd aero_ws_python
pip install -e .

# 或安装发布版本
pip install aero-ws-python
```

## 依赖

- Python 3.8+
- websockets >= 10.0
- pyserial >= 3.5 (仅服务端需要)

## 快速开始

### 作为服务端运行

```python
from aero_ws_python import AeroWebSocketServer
import asyncio

async def main():
    # 创建服务端，连接到ESP32的串口
    server = AeroWebSocketServer(
        host="0.0.0.0",
        port=8765,
        serial_port="COM3",  # Windows
        # serial_port="/dev/ttyUSB0",  # Linux
    )

    # 启动服务
    await server.start()

asyncio.run(main())
```

或直接运行：

```bash
python -m aero_ws_python.server --port 8765 --serial COM3
python -m aero_ws_python.server --port 8765 --fake  # 无硬件测试模式
```

### 作为客户端使用

```python
from aero_ws_python import AeroWebSocketClient

# 连接到服务端
client = AeroWebSocketClient("192.168.1.100", 8765)
client.connect()

# 控制单关节
client.set_joint("index_proximal", 45.0, duration_ms=500)

# 控制多关节同步
client.set_multi_joints([
    {"joint_id": "index_proximal", "angle": 30.0},
    {"joint_id": "index_middle", "angle": 20.0},
    {"joint_id": "index_distal", "angle": 10.0}
], duration_ms=500)

# 归零
client.homing()

client.disconnect()
```

## 协议格式

详见 [CONTROL_PROTOCOL.md](../../protocol/CONTROL_PROTOCOL.md)

## 示例

- `examples/html_client.html` - 浏览器控制面板
- `examples/test_client.py` - Python 测试脚本

## 打包

```bash
# 构建 pip 包
python -m pip install build
python -m build

# 打包成 exe (Windows)
pip install pyinstaller
pyinstaller --onefile --name aero_ws_server server.py
```

## 许可证

Apache-2.0
