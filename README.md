# Aero Hand Open - WebSocket 通信套件

<p align="center">
  <img src="https://img.shields.io/badge/基础项目-Aero%20Hand%20Open-blue" alt="基础项目">
  <img src="https://img.shields.io/badge/许可证-Apache--2.0-green" alt="许可证">
</p>

<p align="center">
  <strong>Aero Hand Open 灵巧机械手的 WebSocket 通信解决方案</strong>
</p>

---

## 项目简介

本项目是 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open) 的衍生项目，提供 **3 种独立的 WebSocket 通信方案**。

视频演示：

https://github.com/user-attachments/assets/f76c292f-73db-4d30-a360-7e2a75dd2fbd

---

## 三种方案概览

| 方案 | 描述 | 固件需求 | 连接方式 |
|------|------|----------|----------|
| **方案一** | Python 桥接（有线） | 无（用原始USB固件） | USB → 舵机控制板 |
| **方案二** | ESP32 无线控制 | 需烧录 ESP32 固件 | WiFi 热点 |
| **方案三** | C DLL 库（跨语言） | 无（库本身） | WiFi → ESP32 服务器 |

---

## 方案一：Python 桥接（有线）

本地运行 Python WebSocket 服务器，通过 USB 连接舵机控制板。

**连接示意**：电脑 → USB → 舵机控制板 → 机械手

**固件需求**：使用原始 USB 固件，无需烧录 ESP32 固件。

**目录**：`python_bridge/`

```bash
cd python_bridge
pip install -e .

# 运行服务（fake模式，无需硬件）
python -m aero_ws_python.server --fake

# 运行服务（连接真实硬件）
python -m aero_ws_python.server --serial COM3 --baudrate 115200
```

**特点**：简单易用，适合开发调试，延迟低，稳定可靠。

---

## 方案二：ESP32 无线控制（无线）

ESP32 独立运行 WebSocket 服务器，实现无线控制。

**连接示意**：手机/电脑 → WiFi → ESP32 → 串口 → 舵机控制板 → 机械手

**固件需求**：需要烧录 ESP32 WiFi 固件。

**目录**：`esp32_wifi/`

### 烧录固件

使用预编译固件（位于 `firmware_bin/`）：

```bash
esptool.py --chip esp32s3 --port COM3 write_flash \
  0x0 firmware_bin/boot_app0.bin \
  0x1000 firmware_bin/aero_hand_wifi.ino.bootloader.bin \
  0x8000 firmware_bin/aero_hand_wifi.ino.partitions.bin \
  0x10000 firmware_bin/aero_hand_wifi.ino.merged.bin
```

### 使用方式

1. ESP32 上电后创建热点 `AeroHand_WIFI`（密码：`12345678`）
2. 手机/电脑连接该热点
3. 打开 `esp32_wifi/web_client/html_client.html` 或使用 Python 客户端连接 `ws://192.168.4.1:8765`

### 固件文件说明

| 文件 | 说明 |
|------|------|
| `aero_hand_wifi.ino.merged.bin` | 完整固件（8MB），包含所有分区，推荐使用 |
| `aero_hand_wifi.ino.bin` | 应用程序（不含bootloader） |
| `aero_hand_wifi.ino.bootloader.bin` | Bootloader |
| `aero_hand_wifi.ino.partitions.bin` | 分区表 |
| `boot_app0.bin` | Boot app0 |

### WiFi 配置

在 `esp32_wifi/firmware/aero_hand_wifi/config.h` 中修改：

```cpp
#define WIFI_MODE 1           // 1=AP热点, 2=STA连接路由器
#define AP_SSID "AeroHand_WIFI"
#define AP_PASSWORD "12345678"
#define WS_PORT 8765
```

---

## 方案三：C DLL 库（跨语言）

编译为动态库，支持 C/C++、C#、Python 等语言调用。

**固件需求**：C DLL 库本身无需固件，但作为 WebSocket **客户端**使用，需要先部署 ESP32 无线方案（方案二）。

**目录**：`c_dll/`

### 构建

```bash
# Linux/macOS
mkdir build && cd build
cmake ..
make

# Windows
mkdir build
cmake .. -G "Visual Studio 17 2022"
cmake --build . --config Release
```

### API 示例

```c
#include "aero_ws.h"

AeroWSHandle handle = aero_ws_create("192.168.4.1", 8765);
aero_ws_connect(handle, 5000);
aero_ws_set_joint(handle, "index_proximal", 45.0f, 500);
aero_ws_disconnect(handle);
aero_ws_destroy(handle);
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

详见 [`protocol/CONTROL_PROTOCOL.md`](protocol/CONTROL_PROTOCOL.md)

---

## 固件版本说明

### 原始固件源代码 (`firmware_src/`)

上游 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open) 各版本固件源码：

| 版本 | 状态 | 说明 |
|------|------|------|
| v0.1.0 | ✅ 完整 | 初始版本 |
| v0.1.1 | ❌ 源码不可用 | 仅二进制固件可用 |
| v0.1.2 | ❌ 源码不可用 | 仅二进制固件可用 |
| v0.1.3 | ✅ 完整 | 添加热保护 |
| v0.1.4 | ✅ 完整 | 优化热保护参数 |
| v0.1.5 | ✅ 完整 | 修复电机配置问题 |
| v0.2.0 | ✅ 完整 | 拇指归位偏移优化 |

### WebSocket 固件 (`firmware_ws/`)

将原始串口固件改造为 WiFi + WebSocket 通信的版本，保留各版本特性。编译后的固件和源码包可在 [Release 1](https://github.com/fanfan142/aero-hand-open_websockets/releases/tag/1) 下载：

| 版本 | 二进制固件 | 源文件包 | 热保护 | 初始扭矩 | 特性 |
|------|-----------|----------|--------|----------|------|
| v0.1.0 | `aero_hand_v0.1.0_websockets.bin` | `v0.1.0_websockets_src.tar.gz` | 无 | 1023 | 基础版本 |
| v0.1.3 | `aero_hand_v0.1.3_websockets.bin` | `v0.1.3_websockets_src.tar.gz` | 50°C / 200 | 700 | 添加热保护 |
| v0.1.4 | `aero_hand_v0.1.4_websockets.bin` | `v0.1.4_websockets_src.tar.gz` | 70°C / 500 | 700 | 更宽松的热保护 |
| v0.1.5 | `aero_hand_v0.1.5_websockets.bin` | `v0.1.5_websockets_src.tar.gz` | 70°C / 500 | 700 | 修复电机配置问题 |
| v0.2.0 | `aero_hand_v0.2.0_websockets.bin` | `v0.2.0_websockets_src.tar.gz` | 70°C / 500 | 700 | 拇指归位偏移优化 |

详见 [`firmware_ws/README.md`](firmware_ws/README.md)

---

## 项目结构

```
aero-hand-open_websockets/
├── python_bridge/           # 方案一：Python WebSocket 桥接（有线）
│   ├── aero_ws_python/    # Python 包源码
│   ├── examples/          # 示例脚本
│   ├── tests/            # 单元测试
│   └── README.md
│
├── esp32_wifi/            # 方案二：ESP32 无线控制（已验证可行）
│   ├── firmware/          # ESP32 固件源码
│   │   └── aero_hand_wifi/
│   ├── web_client/        # Web 客户端（HTML）
│   └── README.md
│
├── c_dll/                 # 方案三：C DLL 跨语言库
│   ├── include/           # 头文件
│   ├── src/              # 源代码
│   └── examples/          # 调用示例
│
├── firmware_bin/           # 预编译 ESP32 固件
├── firmware_src/           # 原始固件源代码（v0.1.0, v0.1.3-v0.2.0）
├── firmware_ws/           # WebSocket 改造固件（v0.1.0, v0.1.3-v0.2.0）
├── protocol/              # 统一通信协议定义
└── README.md / README_en.md
```

---

## 测试

```bash
cd python_bridge
pip install pytest
python -m pytest tests/ -v
```

当前测试状态：**7 passed**

---

## 与上游项目的关系

本项目派生于 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open)。

| 组件 | 上游项目 | 本项目 |
|------|---------|--------|
| 机械硬件 | ✅ | - |
| USB 固件 | ✅ | - |
| Python 桥接 | - | ✅ WebSocket 扩展 |
| ESP32 无线固件 | - | ✅ 新增 |
| C DLL 库 | - | ✅ 新增 |

---

## 许可证

- **软件代码**：Apache-2.0
- **上游硬件设计文件**：CC BY-NC-SA 4.0

详见 [LICENSE.md](LICENSE.md)

---

## 相关链接

- **本项目**：https://github.com/fanfan142/aero-hand-open_websockets
- **上游项目**：https://github.com/TetherIA/aero-hand-open

---

© 2026 fanfan142 | 基于 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open)
