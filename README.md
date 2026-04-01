# Aero Hand Open - WebSocket 通信套件

<p align="center">
  <img src="https://img.shields.io/badge/基础项目-Aero%20Hand%20Open-blue" alt="基础项目">
  <img src="https://img.shields.io/badge/许可证-Apache--2.0-green" alt="许可证">
  <img src="https://img.shields.io/badge/Python-3.8+-orange" alt="Python">
  <img src="https://img.shields.io/badge/ESP32-S3-brightgreen" alt="ESP32">
</p>

<p align="center">
  <strong>Aero Hand Open 灵巧机械手的 WebSocket 通信解决方案</strong>
</p>

---

## 项目简介

本项目是 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open) 的衍生项目，专注于为 Aero Hand Open 机械手提供全面的 **WebSocket 通信解决方案**。

通过统一的 JSON 通信协议，实现无线控制、跨平台集成和多语言支持。

### 核心功能

- **WiFi 无线控制** - 使用 ESP32-S3 通过 WiFi 控制机械手
- **Python SDK** - 完整的 Python 包，包含 WebSocket 服务器和客户端
- **C DLL 库** - 支持 C/C++、C#、Python (ctypes) 的跨语言调用
- **浏览器控制** - 基于 HTML 的控制面板，快速测试
- **统一协议** - 基于 JSON 的 15 个关节控制协议

---

## 项目结构

```
aero-hand-open_websockets/
├── aero_ws_python/          # Python WebSocket SDK
│   ├── __init__.py          # 包入口
│   ├── client.py            # WebSocket 客户端
│   ├── server.py            # WebSocket 服务器
│   ├── protocol.py          # 协议定义
│   ├── examples/            # 示例代码
│   │   ├── html_client.html # 浏览器控制面板
│   │   ├── test_client.py  # 基础测试
│   │   ├── interactive_test.py  # 交互式测试
│   │   └── connection_test.py   # 连接测试
│   └── tests/               # 单元测试
│
├── c_dll/                   # C DLL 动态库
│   ├── include/             # 头文件
│   │   ├── aero_ws.h       # 公共 API
│   │   └── aero_ws_internal.h
│   ├── src/                # 源代码实现
│   │   └── aero_ws.c
│   └── examples/           # 调用示例
│       ├── python_wrapper.py  # Python ctypes 示例
│       └── csharp_example.cs # C# P/Invoke 示例
│
├── esp32_firmware/          # ESP32 WiFi 固件
│   └── aero_hand_wifi/     # Arduino 项目
│       ├── aero_hand_wifi.ino  # 主程序
│       ├── config.h        # WiFi 配置
│       ├── webSocketServer.* # WebSocket 处理器
│       ├── servoControl.*  # 舵机控制
│       ├── SCS.*           # 串口通信
│       ├── Homing.*        # 归零校准
│       └── README.md       # 固件文档
│
├── firmware_bin/            # 预编译固件
│   ├── aero_hand_wifi.ino.merged.bin  # 完整固件 (8MB)
│   ├── aero_hand_wifi.ino.bin         # 应用程序
│   ├── aero_hand_wifi.ino.bootloader.bin
│   ├── aero_hand_wifi.ino.partitions.bin
│   └── boot_app0.bin
│
└── protocol/               # 通信协议
    └── CONTROL_PROTOCOL.md # JSON 协议文档
```

---

## 快速开始

### 方案 A：Python WebSocket 桥接（推荐开发用）

```bash
cd aero_ws_python
pip install -e .

# 测试模式（无需硬件）
python -m aero_ws_python.server --fake

# 连接真实硬件
python -m aero_ws_python.server --serial COM3 --baudrate 115200
```

### 方案 B：ESP32 独立固件（无线控制）

1. 使用 esptool 烧录固件：
```bash
esptool.py --chip esp32s3 --port COM3 write_flash 0x0 firmware_bin/boot_app0.bin 0x1000 firmware_bin/aero_hand_wifi.ino.bootloader.bin 0x8000 firmware_bin/aero_hand_wifi.ino.partitions.bin 0x10000 firmware_bin/aero_hand_wifi.ino.merged.bin
```

2. 连接 WiFi 热点 `AeroHand_WIFI`（密码：`12345678`）

3. 在浏览器中打开 `aero_ws_python/examples/html_client.html`

### 方案 C：C DLL 库（生产环境）

```bash
# Linux/macOS 构建
mkdir build && cd build
cmake ..
make

# Windows 构建 (Visual Studio)
cmake .. -G "Visual Studio 17 2022"
cmake --build . --config Release
```

---

## 关节定义

Aero Hand Open 共有 **15 个关节**：

| 关节 ID | 描述 | 舵机 ID | 角度范围 |
|---------|------|---------|----------|
| thumb_proximal | 拇指近端 | 0 | 0° ~ 90° |
| thumb_distal | 拇指远端 | 1 | 0° ~ 90° |
| index_proximal | 食指近端 | 2 | 0° ~ 90° |
| index_middle | 食指中端 | 3 | 0° ~ 90° |
| index_distal | 食指远端 | 4 | 0° ~ 90° |
| middle_proximal | 中指近端 | 5 | 0° ~ 90° |
| middle_middle | 中指中端 | 6 | 0° ~ 90° |
| middle_distal | 中指远端 | 7 | 0° ~ 90° |
| ring_proximal | 无名指近端 | 8 | 0° ~ 90° |
| ring_middle | 无名指中端 | 9 | 0° ~ 90° |
| ring_distal | 无名指远端 | 10 | 0° ~ 90° |
| pinky_proximal | 小指近端 | 11 | 0° ~ 90° |
| pinky_middle | 小指中端 | 12 | 0° ~ 90° |
| pinky_distal | 小指远端 | 13 | 0° ~ 90° |
| thumb_rotation | 拇指旋转 | 14 | -30° ~ 30° |

---

## 通信协议

### 控制指令格式

```json
{
  "type": "multi_joint_control",
  "timestamp": 1711641600000,
  "data": {
    "joints": [
      {"joint_id": "index_proximal", "angle": 45.0},
      {"joint_id": "index_middle", "angle": 30.0},
      {"joint_id": "index_distal", "angle": 15.0}
    ],
    "duration_ms": 500
  }
}
```

详细协议文档请参阅 [`protocol/CONTROL_PROTOCOL.md`](protocol/CONTROL_PROTOCOL.md)

---

## API 参考

### Python SDK

```python
from aero_ws_python import AeroWebSocketClient, AeroWebSocketServer

# 客户端用法
client = AeroWebSocketClient("192.168.4.1", 8765)
client.connect()
client.set_multi_joints([
    {"joint_id": "index_proximal", "angle": 45.0},
    {"joint_id": "index_middle", "angle": 30.0}
], duration_ms=500)
client.disconnect()

# 服务器用法
server = AeroWebSocketServer(port=8765, serial_port="COM3")
asyncio.run(server.start())
```

### C DLL

```c
#include "aero_ws.h"

AeroWSHandle handle = aero_ws_create("192.168.4.1", 8765);
aero_ws_connect(handle, 5000);
aero_ws_set_joint(handle, "index_proximal", 45.0f, 500);
aero_ws_disconnect(handle);
aero_ws_destroy(handle);
```

---

## 硬件要求

### ESP32 固件

- **开发板**: Seeed XIAO ESP32S3 或兼容板
- **电源**: 12V DC 输入 + 5V 稳压器
- **连接**: TX/GPIO43 → 舵机控制板 RX, RX/GPIO44 → TX

### Python 桥接

- USB 连接舵机控制板
- Python 3.8+
- pyserial 库

---

## 测试

```bash
cd aero_ws_python
pip install pytest
python -m pytest tests/ -v
```

当前测试状态：**7 passed**

---

## 与上游项目的关系

本项目派生于 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open)，专注于扩展其通信能力：

| 组件 | 上游项目 | 本项目 |
|------|---------|--------|
| 机械硬件 | ✅ 完整 | - |
| USB 固件 | ✅ 完整 | - |
| Python SDK | ✅ 串口控制 | **WebSocket 扩展** |
| ESP32 固件 | ❌ 无 | **✅ WiFi WebSocket** |
| C DLL | ❌ 无 | **✅ 跨语言调用** |

---

## 许可证

- **软件代码（Python、C、ESP32 固件）**: [Apache-2.0](LICENSE.md)
- **通信协议文档**: [Apache-2.0](LICENSE.md)

本项目派生于 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open)，其硬件设计文件采用 **CC BY-NC-SA 4.0** 许可证。

详见 [LICENSE.md](LICENSE.md)。

---

## 贡献指南

欢迎贡献！请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 致谢

- **TetherIA** - 设计和开源了 Aero Hand Open 机械手
- **上游项目**: https://github.com/TetherIA/aero-hand-open

---

## 相关链接

- **本项目**: https://github.com/fanfan142/aero-hand-open_websockets
- **上游项目**: https://github.com/TetherIA/aero-hand-open
- **官方文档**: https://docs.tetheria.ai/

---

© 2026 fanfan142 | 基于 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open)
