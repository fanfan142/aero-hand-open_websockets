# WebSocket 固件 (firmware_ws)

本目录包含将原始串口固件改造为 WiFi + WebSocket 通信的版本。

## 目录结构

```
firmware_ws/
├── README.md              # 本文件
├── generate_ws_firmware.py # 生成脚本
├── config.h               # 通用配置
├── webSocketServer.h      # WebSocket服务器头文件
├── webSocketServer.cpp    # WebSocket服务器实现
├── v0.1.0/               # 原始版本 v0.1.0 + WebSocket
│   ├── firmware.ino       # 主程序
│   ├── HandConfig.h      # 原始文件
│   ├── Homing.cpp        # 原始文件
│   └── Homing.h          # 原始文件
├── v0.1.3/               # 原始版本 v0.1.3 + WebSocket
├── v0.1.4/               # 原始版本 v0.1.4 + WebSocket
└── v0.1.5/               # 原始版本 v0.1.5 + WebSocket
```

## 版本差异

| 版本 | 热保护 | 初始扭矩 | 特性 |
|------|--------|----------|------|
| v0.1.0 | 无 | 1023 | 基础版本 |
| v0.1.3 | 50°C / 200 | 700 | 添加热保护 |
| v0.1.4 | 70°C / 500 | 700 | 更宽松的热保护 |
| v0.1.5 | 70°C / 500 | 700 | 修复电机配置问题 |

## 改造说明

### 主要变更

1. **通信方式变更**
   - 原始: Serial (USB) 16字节二进制协议
   - 改造后: WiFi + WebSocket + JSON协议

2. **保留的核心功能**
   - HLSCL 舵机控制库
   - TaskSyncRead_Core1 核心1同步读取任务
   - 软限位保护
   - 热保护功能 (v0.1.3+)
   - Homing 归位模块

3. **新增功能**
   - WiFi AP/STA 模式
   - WebSocket 服务器
   - JSON 命令解析

### 代码变更说明

在原始代码中，需要变更的部分已用注释标记：

```cpp
// ============================================
// WiFi / WebSocket 相关头文件 (新增)
// ============================================
#include <WiFi.h>
#include <WebSocketsServer.h>
#include <ArduinoJson.h>
#include "webSocketServer.h"
```

setup() 和 loop() 中的变更：

```cpp
// 原有串口初始化 (已注释)
// Serial.begin(921600);

// 新增 WiFi 初始化
setupWiFi();
wsServer.begin(WS_PORT);

// 原有 loop 中的串口处理 (已注释)
// if (Serial.available() >= 16) { ... }

// 新增 WebSocket 处理
wsServer.loop();
```

## WebSocket 命令协议

### 连接

连接地址: `ws://<ESP32_IP>:8765/`

### 命令格式

```json
// 单关节控制
{"type": "joint_control", "data": {"joint_id": "index_proximal", "angle": 45.0, "duration_ms": 500}}

// 多关节控制
{"type": "multi_joint_control", "data": {"joints": [{"joint_id": "thumb_proximal", "angle": 30.0}], "duration_ms": 500}}

// 归位
{"type": "homing"}

// 获取状态
{"type": "get_states"}
```

### 响应格式

```json
// 成功响应
{"type": "response", "success": true, "timestamp": 12345, "data": {"executed": true}}

// 错误响应
{"type": "response", "success": false, "timestamp": 12345, "error": {"code": "COMMAND_ERROR", "message": "Invalid joint_id"}}

// 状态响应
{"type": "states_response", "success": true, "timestamp": 12345, "data": [...]}
```

## 编译

使用 PlatformIO:

```bash
cd firmware_ws/v0.1.x
pio run
```

或使用 Arduino IDE 将 `firmware_ws/v0.1.x/` 目录作为项目打开。

## 配置

在 `config.h` 中修改:

```cpp
// WiFi 模式: 1=AP, 2=STA
#define WIFI_MODE 1

// AP 模式配置
#define AP_SSID "AeroHand_WIFI"
#define AP_PASSWORD "12345678"

// WebSocket 端口
#define WS_PORT 8765
```

---
*最后更新: 2026-04-02*
