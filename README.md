# Aero Hand Open - WebSocket Communication Suite

![Project Banner](assets/banner.png)

<p align="center">
  <a href="https://github.com/TetherIA/aero-hand-open"><img src="https://img.shields.io/badge/Base%20Project-Aero%20Hand%20Open-blue" alt="Base Project"></a>
  <a href="https://github.com/fanfan142/aero-hand-open_websockets"><img src="https://img.shields.io/badge/GitHub-fanfan142-orange" alt="Fork"></a>
</p>

## 项目起源 | Project Origin

本项目是 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open) 的衍生项目，专注于为 Aero Hand Open 机械手提供 **WebSocket 通信解决方案**。

Aero Hand Open 是一款由 TetherIA 设计的开源、肌腱驱动灵巧机械手，专注于简单性、可靠性和可访问性。本项目扩展了其通信能力，支持无线控制和跨平台调用。

> **原始项目**: https://github.com/TetherIA/aero-hand-open
> **官方文档**: https://docs.tetheria.ai/

## 核心功能 | Key Features

- **方案A - Python WebSocket 桥接**: 上位机运行 Python 服务，通过 USB 连接舵机控制板
- **方案B-ESP32 - 独立固件**: ESP32-S3 独立运行 WebSocket 服务器，实现无线控制
- **方案B-C DLL - 跨语言调用**: C 语言动态库，支持 Python/C# 等语言直接调用
- **统一通信协议**: 基于 JSON 的关节控制协议，支持单关节/多关节同步控制

## 系统架构 | System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                           上位机 / 工控机                            │
│                                                                         │
│   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                 │
│   │  Python SDK │   │   C DLL     │   │  HTML Client │                 │
│   │  (client)   │   │  (C#/Python)│   │  (浏览器)    │                 │
│   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘                 │
│          │                  │                  │                         │
│          └──────────────────┼──────────────────┘                         │
│                             │                                            │
│                             ▼                                            │
│                    ┌─────────────────┐                                   │
│                    │  WebSocket      │                                   │
│                    │  ws://:8765     │                                   │
│                    └────────┬────────┘                                   │
└─────────────────────────────┼───────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│   方案A        │   │   方案B       │   │   方案B       │
│ Python桥接     │   │ ESP32 WiFi    │   │ C DLL直连     │
│               │   │               │   │               │
│  USB/TTL     │   │  WiFi无线     │   │ (暂不支持)    │
└───────────────┘   └───────────────┘   └───────────────┘
```

## 目录结构 | Directory Structure

```
aero-hand-open_websockets/
├── aero_ws_python/              # Python WebSocket SDK
│   ├── src/aero_ws_python/     # 源代码包
│   ├── examples/               # 示例代码
│   │   ├── html_client.html    # 浏览器控制面板
│   │   ├── test_client.py      # 简单测试
│   │   ├── interactive_test.py  # 交互式测试
│   │   └── connection_test.py   # 连接测试
│   ├── tests/                  # 单元测试
│   ├── pyproject.toml          # 项目配置
│   └── README.md               # Python SDK 说明
│
├── c_dll/                      # C 语言 DLL 库
│   ├── include/                # 头文件
│   ├── src/                   # 源代码
│   ├── examples/              # 调用示例
│   │   ├── python_wrapper.py   # Python 调用
│   │   └── csharp_example.cs  # C# 调用
│   ├── CMakeLists.txt         # CMake 配置
│   └── README.md              # C DLL 说明
│
├── esp32_firmware/            # ESP32 独立固件
│   └── aero_hand_wifi/        # Arduino 项目
│       ├── aero_hand_wifi.ino  # 主程序
│       ├── config.h           # 配置文件
│       ├── webSocketServer.*  # WebSocket 处理
│       ├── servoControl.*     # 舵机控制
│       └── README.md          # 固件说明
│
├── protocol/                   # 通信协议定义
│   └── CONTROL_PROTOCOL.md     # JSON 协议文档
│
├── docs/                       # 扩展文档
│
└── README.md                   # 本文件
```

## 快速开始 | Quick Start

### 方案A：Python WebSocket 桥接（推荐开发用）

```bash
cd aero_ws_python
pip install -e .

# 测试模式（无需硬件）
python -m aero_ws_python.server --fake

# 连接真实硬件
python -m aero_ws_python.server --serial COM3 --baudrate 115200
```

### 方案B：ESP32 独立固件（无线控制）

1. 使用 Arduino IDE 打开 `esp32_firmware/aero_hand_wifi/aero_hand_wifi.ino`
2. 安装依赖库：ArduinoJson
3. 选择开发板：XIAO_ESP32S3
4. 上传固件
5. 连接热点 `AeroHand_WIFI`（密码：12345678）
6. 打开浏览器访问 `examples/html_client.html`

### 方案B：C DLL 库（生产环境）

```bash
mkdir build && cd build
cmake ..
cmake --build . --config Release
# 输出: build/Release/aero_ws.dll
```

## 关节定义 | Joint Definitions

Aero Hand Open 共有 **15个关节**：

| joint_id | 描述 | 舵机ID | 角度范围 |
|----------|------|--------|----------|
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

## 通信协议 | Communication Protocol

### 控制指令格式

```json
{
  "type": "multi_joint_control",
  "timestamp": 1711641600000,
  "data": {
    "joints": [
      {"joint_id": "index_proximal", "angle": 45.0},
      {"joint_id": "index_middle", "angle": 30.0}
    ],
    "duration_ms": 500
  }
}
```

详见 [`protocol/CONTROL_PROTOCOL.md`](protocol/CONTROL_PROTOCOL.md)

## 技术规格 | Specifications

| 参数 | 值 |
|------|-----|
| 支持关节数 | 15 |
| WebSocket 默认端口 | 8765 |
| 串口默认波特率 | 115200 |
| ESP32 WiFi 模式 | AP热点 |
| ESP32 默认热点名 | AeroHand_WIFI |
| ESP32 默认密码 | 12345678 |

## 与上游项目的关系 | Relationship with Upstream

本项目基于 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open) 开发，专注于扩展其通信能力：

| 组件 | 上游项目 | 本项目 |
|------|----------|--------|
| 机械硬件 | ✅ 完整 | - |
| 固件 (USB) | ✅ 完整 | - |
| Python SDK | ✅ 串口控制 | **WebSocket 扩展** |
| ESP32 固件 | ❌ 无 | **✅ 独立 WiFi 控制** |
| C DLL | ❌ 无 | **✅ 跨语言调用** |

## 获取帮助 | Getting Help

- **上游项目 Issues**: https://github.com/TetherIA/aero-hand-open/issues
- **本项目 Issues**: https://github.com/fanfan142/aero-hand-open_websockets/issues
- **上游 Discussions**: https://github.com/TetherIA/aero-hand-open/discussions

## 许可证 | License

本项目采用以下许可证：

- **软件代码（Python、C、ESP32固件）**: [Apache-2.0](LICENSE.md#apache-20)
- **通信协议文档**: [Apache-2.0](LICENSE.md#apache-20)

本项目派生于 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open)，原始项目的硬件设计文件采用 **CC BY-NC-SA 4.0** 许可证。

详见 [LICENSE.md](LICENSE.md)

---

## 致谢 | Acknowledgments

- **TetherIA**: 设计并开源了 Aero Hand Open 机械手
- **上游项目地址**: https://github.com/TetherIA/aero-hand-open

---

© 2025 fanfan142 | 基于 TetherIA Aero Hand Open
