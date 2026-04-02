# Aero Hand Open - WebSocket Communication Suite

本项目是 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open) 的衍生项目，为 Aero Hand Open 机械手提供 WebSocket 通信解决方案。

## 项目结构

```
aero-hand-open_websockets/
├── c_dll/                    # C DLL 库 (跨语言支持)
├── docs/                     # 文档
├── esp32_wifi/               # ESP32 WiFi 方案
│   └── firmware/             # ESP32 固件源码
├── firmware_bin/             # 预编译固件 (lefthand/righthand)
├── firmware_src/             # 原始 USB 固件源码 (v0.1.0-v0.2.0)
├── firmware_ws/              # WebSocket 改造固件 (各版本独立目录)
│   ├── lib/                  # 共享库文件
│   ├── v0.1.0/              # v0.1.0 版本
│   ├── v0.1.3/              # v0.1.3 版本
│   ├── v0.1.4/              # v0.1.4 版本
│   ├── v0.1.5/              # v0.1.5 版本
│   └── v0.2.0/              # v0.2.0 版本
├── protocol/                 # 通信协议文档
├── python_bridge/            # Python 桥接方案
└── README.md                 # 中文文档
```

## 版本说明

### 固件版本差异

| 版本 | 热保护 | 初始扭矩 | 主要特性 |
|------|--------|----------|----------|
| v0.1.0 | 无 | 1023 | 基础版本 |
| v0.1.3 | 50°C/200 | 700 | 添加热保护 |
| v0.1.4 | 70°C/500 | 700 | 优化热保护参数 |
| v0.1.5 | 70°C/500 | 700 | 修复电机配置 |
| v0.2.0 | 70°C/500 | 700 | 拇指归位偏移优化 |

### WebSocket 改造说明

- 将原始串口通信 (Serial) 替换为 WiFi + WebSocket
- 保留所有核心功能: HLSCL 舵机控制、TaskSyncRead_Core1、软限位
- 新增 JSON 格式命令解析

## 三种通信方案

| 方案 | 描述 | 固件 | 连接方式 |
|------|------|------|----------|
| 1 - Python Bridge | Python WebSocket 服务器 | 原始 USB 固件 | USB |
| 2 - ESP32 WiFi | ESP32 独立 WebSocket 服务器 | 需要烧录 | WiFi |
| 3 - C DLL | 跨语言动态库 | 无固件要求 | WiFi |

## 构建说明

### WebSocket 固件构建

使用 `firmware_ws_build/build_versions.py` 脚本构建所有版本:

```bash
cd firmware_ws_build
python build_versions.py
```

### ESP32 WiFi 固件

使用 PlatformIO:

```bash
cd esp32_wifi/firmware/aero_hand_wifi
pio run
```

## 技术栈

- **ESP32-S3** - 主控芯片
- **Arduino Framework** - 开发框架
- **WebSockets** - 通信协议
- **ArduinoJson** - JSON 解析
- **PlatformIO** - 构建系统

## 许可证

- 软件代码: Apache-2.0
- 硬件设计文件: CC BY-NC-SA 4.0
