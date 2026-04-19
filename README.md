# Aero Hand Open - WebSocket 控制套件

<p align="center">
  <img src="https://img.shields.io/badge/基础项目-Aero%20Hand%20Open-blue" alt="基础项目">
  <img src="https://img.shields.io/badge/许可证-Apache--2.0-green" alt="许可证">
  <img src="https://img.shields.io/badge/版本-v1.4.0-orange" alt="版本">
</p>

<p align="center">
  <strong>Aero Hand Open 灵巧机械手的 Android 控制 + WebSocket 通信方案</strong>
</p>

---

## 视频演示

https://github.com/user-attachments/assets/f76c292f-73db-4d30-a360-7e2a75dd2fbd

---

## 快速开始

### Android 控制应用（推荐）

从 [Releases](https://github.com/fanfan142/aero-hand-open_websockets/releases) 下载 `HandControl-x.x.x.apk` 安装到 Android 手机。

**控制方式：**

| 模式 | 连接 | 说明 |
|------|------|------|
| **WiFi WebSocket** | 连接 ESP32 热点 | 无线控制，推荐使用 |
| **USB OTG 串口** | 手机 USB → 机械手 | 有线控制，无需 ESP32 |

**Android 功能：**
- 7 通道紧凑控制 / 15 关节展开控制
- 手势识别实时控制
- 预设动作库（张开、抓握、捏取、OK、剪刀手、点赞、石头/布/剪刀等）
- WiFi AP / STA / DUAL 模式动态切换
- 左右手自动识别

---

## 固件烧录

ESP32-S3 固件烧录到 `esp32_wifi/firmware/aero_hand_wifi/` 目录：

```bash
esptool.py --chip esp32s3 --port COM3 write_flash \
  0x0 firmware_bin/boot_app0.bin \
  0x1000 firmware_bin/aero_hand_wifi.ino.bootloader.bin \
  0x8000 firmware_bin/aero_hand_wifi.ino.partitions.bin \
  0x10000 firmware_bin/aero_hand_wifi.ino.merged.bin
```

**WiFi 默认配置：**

| 配置 | 值 |
|------|------|
| 热点名称 | `AeroHand_WIFI` |
| 热点密码 | `12345678` |
| WebSocket 端口 | `8765` |

**WiFi 固件区分左右手：**
- 左手机械手：热点名称 `AeroHand_Left`
- 右手机械手：热点名称 `AeroHand_Right`

---

## 固件版本

| 版本 | 热保护 | 初始扭矩 | 特性 |
|------|--------|----------|------|
| v0.1.0 | 无 | 1023 | 基础版本 |
| v0.1.3 | 50°C / 200 | 700 | 添加热保护 |
| v0.1.4 | 70°C / 500 | 700 | 放宽热保护 |
| v0.1.5 | 70°C / 500 | 700 | 电机配置修复 |
| v0.2.0 | 70°C / 500 | 700 | 拇指归位偏移优化 |

预编译固件（左右手各版本）：[Releases](https://github.com/fanfan142/aero-hand-open_websockets/releases)

---

## 关节定义

Aero Hand Open 共有 **15 个关节 + 1 个拇指旋转**：

| 关节 ID | 描述 | 角度范围 |
|---------|------|----------|
| thumb_proximal | 拇指近端 | 0° ~ 90° |
| thumb_distal | 拇指远端 | 0° ~ 90° |
| index_proximal / middle / distal | 食指三节 | 0° ~ 90° |
| middle_proximal / middle / distal | 中指三节 | 0° ~ 90° |
| ring_proximal / middle / distal | 无名指三节 | 0° ~ 90° |
| pinky_proximal / middle / distal | 小指三节 | 0° ~ 90° |
| thumb_rotation | 拇指旋转 | -30° ~ 30° |

---

## WebSocket 协议

连接地址：`ws://192.168.4.1:8765`（默认 AP 模式）

**单关节控制：**

```json
{
  "type": "joint_control",
  "data": {
    "joint_id": "index_proximal",
    "angle": 45.0,
    "duration_ms": 500
  }
}
```

**多关节控制：**

```json
{
  "type": "multi_joint_control",
  "data": {
    "joints": [
      {"joint_id": "thumb_proximal", "angle": 30.0},
      {"joint_id": "index_proximal", "angle": 60.0}
    ],
    "duration_ms": 500
  }
}
```

**归零 / 获取状态：**

```json
{"type": "homing"}
{"type": "get_states"}
```

详见 [`protocol/CONTROL_PROTOCOL.md`](protocol/CONTROL_PROTOCOL.md)

---

## 项目结构

```
aero-hand-open_websockets/
├── android/                  # Android 控制应用
│   └── app/src/main/java/com/aerohand/
│       ├── websocket/        # WebSocket 服务
│       ├── usb/             # USB 串口服务
│       ├── gesture/         # 手势识别
│       └── ui/             # 控制界面
│
├── esp32_wifi/              # ESP32 无线固件
│   └── firmware/aero_hand_wifi/
│
├── firmware_ws/            # WebSocket 改造固件（v0.1.0~v0.2.0）
│
├── python_bridge/           # Python 有线桥接
│
├── c_dll/                   # C DLL 跨语言库
│
├── protocol/               # 通信协议定义
└── firmware_bin/           # 预编译固件
```

---

## 三种通信方案

| 方案 | 描述 | 固件需求 |
|------|------|----------|
| **Android 应用** | Android 原生控制，WiFi / USB | ESP32 固件（WiFi模式）/ 原始USB固件 |
| **Python 桥接** | USB 有线控制 | 原始 USB 固件 |
| **ESP32 无线** | WiFi 热点直连 | ESP32 WiFi 固件 |
| **C DLL** | 跨语言调用 | ESP32 WiFi 固件 |

---

## 许可证

- **软件代码**：Apache-2.0
- **硬件设计文件**：CC BY-NC-SA 4.0

详见 [LICENSE.md](LICENSE.md)

---

## 相关链接

- **本项目**：https://github.com/fanfan142/aero-hand-open_websockets
- **上游项目**：https://github.com/TetherIA/aero-hand-open

---

© 2026 fanfan142 | 基于 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open)
