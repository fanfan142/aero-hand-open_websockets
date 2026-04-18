# Aero Hand Open - Python WebSocket 桥接

**方案一**：本地 Python WebSocket 桥接服务，通过 USB 连接舵机控制板。

## 架构

```
PC (Python桥接程序)
    │
    │ USB
    ▼
舵机控制板 → 机械手
```

**固件需求**：使用原始 USB 固件，无需烧录 ESP32 固件。

## 安装

```bash
cd python_bridge
pip install -e .
```

## 依赖

- Python 3.8+
- websockets >= 10.0
- pyserial >= 3.5

## 快速开始

### 服务端模式（连接舵机控制板）

```python
from aero_ws_python import AeroWebSocketServer
import asyncio

async def main():
    server = AeroWebSocketServer(
        host="0.0.0.0",
        port=8765,
        serial_port="COM3",  # Windows
        # serial_port="/dev/ttyUSB0",  # Linux
    )
    await server.start()

asyncio.run(main())
```

或命令行运行：

```bash
python -m aero_ws_python.server --serial COM3 --baudrate 115200
python -m aero_ws_python.server --fake  # 无硬件测试模式
```

### 客户端模式（连接到 ESP32）

```python
from aero_ws_python import AeroWebSocketClient

client = AeroWebSocketClient("192.168.4.1", 8765)
client.connect()
client.set_multi_joints([
    {"joint_id": "index_proximal", "angle": 45.0},
    {"joint_id": "index_middle", "angle": 30.0}
], duration_ms=500)
client.disconnect()
```

## 示例

位于 `examples/` 目录：

- `test_client.py` - 基础测试
- `interactive_test.py` - 交互式测试
- `connection_test.py` - 连接测试

## 测试

```bash
pip install pytest
python -m pytest tests/ -v
```

## 协议格式

详见 [`../protocol/CONTROL_PROTOCOL.md`](../protocol/CONTROL_PROTOCOL.md)

## 许可证

Apache-2.0
