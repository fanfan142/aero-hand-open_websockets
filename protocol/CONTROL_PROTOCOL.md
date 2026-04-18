# Aero Hand Open - 通信协议文档

*Created: 2026/3/28*
*Version: 1.0*

---

## 1. 概述

本文档定义 Aero Hand Open 机械手的上位机通信协议，支持两种传输模式：

| 模式 | 传输层 | 端口 | 说明 |
|------|--------|------|------|
| WebSocket | TCP | 8765 | 建议用于局域网/无线控制 |
| Serial | USB/TTL | COMx, 115200 baud | 建议用于有线直连 |

---

## 2. 控制指令格式

### 2.1 WebSocket JSON 格式

**单关节控制：**
```json
{
  "type": "joint_control",
  "timestamp": 1711641600000,
  "data": {
    "joint_id": "index_proximal",
    "angle": 45.0,
    "duration_ms": 500
  }
}
```

**多关节同步控制（推荐）：**
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

**查询关节状态：**
```json
{
  "type": "get_states",
  "timestamp": 1711641600000
}
```

**归零/回原点：**
```json
{
  "type": "homing",
  "timestamp": 1711641600000
}
```

### 2.2 关节ID映射表

| joint_id | 描述 | 舵机ID |
|----------|------|--------|
| thumb_proximal | 拇指近端 | 0 |
| thumb_distal | 拇指远端 | 1 |
| index_proximal | 食指近端 | 2 |
| index_middle | 食指中端 | 3 |
| index_distal | 食指远端 | 4 |
| middle_proximal | 中指近端 | 5 |
| middle_middle | 中指中端 | 6 |
| middle_distal | 中指远端 | 7 |
| ring_proximal | 无名指近端 | 8 |
| ring_middle | 无名指中端 | 9 |
| ring_distal | 无名指远端 | 10 |
| pinky_proximal | 小指近端 | 11 |
| pinky_middle | 小指中端 | 12 |
| pinky_distal | 小指远端 | 13 |
| thumb_rotation | 拇指旋转 | 14 |

### 2.3 响应格式

**成功响应：**
```json
{
  "type": "response",
  "success": true,
  "timestamp": 1711641600100,
  "data": {
    "command_type": "joint_control",
    "executed": true
  }
}
```

**状态响应（查询指令）：**
```json
{
  "type": "states_response",
  "success": true,
  "timestamp": 1711641600100,
  "data": {
    "joints": [
      {"joint_id": "index_proximal", "angle": 45.0, "load": 0.3},
      {"joint_id": "index_middle", "angle": 30.0, "load": 0.2}
    ]
  }
}
```

**错误响应：**
```json
{
  "type": "response",
  "success": false,
  "timestamp": 1711641600100,
  "error": {
    "code": "INVALID_ANGLE",
    "message": "Angle 150.0 exceeds maximum limit 90.0"
  }
}
```

### 2.4 错误码表

| 错误码 | 说明 |
|--------|------|
| INVALID_ANGLE | 角度值超出范围 |
| INVALID_JOINT_ID | 无效的关节ID |
| SERVO_ERROR | 舵机通信错误 |
| TIMEOUT | 指令执行超时 |
| SERIAL_ERROR | 串口通信错误 |

---

## 3. 角度范围定义

| 关节 | 最小角度 | 最大角度 | 默认角度 |
|------|----------|----------|----------|
| proximal (近端) | 0° | 90° | 0° |
| middle (中端) | 0° | 90° | 0° |
| distal (远端) | 0° | 90° | 0° |
| thumb_rotation | -30° | 30° | 0° |

---

## 4. 串口二进制协议（可选）

如对方工控机需要直接发送串口指令，可使用以下二进制格式：

| 字节位置 | 内容 | 说明 |
|----------|------|------|
| 0 | 0x55 | 包头 |
| 1 | 0x01 | 版本号 |
| 2 | servo_id | 舵机ID (0-14) |
| 3 | angle_low | 角度低8位 |
| 4 | angle_high | 角度高8位 |
| 5 | checksum | 校验和 (XOR) |

**角度计算：**
```
angle_low = angle & 0xFF
angle_high = (angle >> 8) & 0xFF
checksum = 0x55 ^ 0x01 ^ servo_id ^ angle_low ^ angle_high
```

**波特率：** 115200

---

## 5. 使用示例

### 5.1 Python 调用示例

```python
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
print(states)

client.disconnect()
```

### 5.2 C DLL 调用示例

```c
#include "aero_ws.h"

// 初始化
AeroWSHandle handle = aero_ws_create();
aero_ws_connect(handle, "192.168.1.100", 8765);

// 控制关节
AeroJoint joints[] = {{"index_proximal", 45.0}};
aero_ws_set_joints(handle, joints, 1, 500);

// 获取状态
AeroStates states;
aero_ws_get_states(handle, &states);

// 销毁
aero_ws_destroy(handle);
```

---

## 6. 技术参数

| 参数 | 值 |
|------|-----|
| 建议控制频率 | 20-50 Hz |
| 单指令处理延迟 | < 20 ms |
| WiFi 传输距离 (AP模式) | < 5m |
| WiFi 频段 | 2.4 GHz |
| 串口波特率 | 115200 |

---

*最后更新：2026-03-28*
