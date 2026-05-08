# Aero Hand Open WebSocket 控制套件

<p align="center">
  <img src="https://img.shields.io/badge/project-Aero%20Hand%20Open-blue" alt="project">
  <img src="https://img.shields.io/badge/license-Apache--2.0-green" alt="license">
  <img src="https://img.shields.io/badge/android-CI%20ready-brightgreen" alt="android ci">
</p>

<p align="center">
  <strong>面向 Aero Hand Open 灵巧手的 Android、ESP32 WiFi、Python Bridge 与 C DLL WebSocket 通信方案。</strong>
</p>

---

## 项目定位

本仓库是 [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open) 的 WebSocket 通信扩展，目标是让 Aero Hand Open 灵巧手可以通过手机、电脑脚本或跨语言 DLL 进行稳定控制。

它不是机械本体仓库，而是围绕原始灵巧手控制板和 ESP32-S3 构建的通信中间件与控制应用。

```
Android / PC / 脚本
        │
        ├── WiFi WebSocket ──> ESP32-S3 ──> 舵机总线 ──> Aero Hand
        │
        ├── USB OTG 串口 ───> 原始控制板 ──> 舵机总线 ──> Aero Hand
        │
        └── Python Bridge / C DLL ──> WebSocket / USB ──> Aero Hand
```

---

## 当前通信方案

| 方案 | 目录 | 适用场景 | 硬件/固件需求 |
|------|------|----------|---------------|
| Android 控制 App | `android/` | 手机直接控制、手势跟随、预设动作 | WiFi 模式需 ESP32 固件；USB 模式需 OTG |
| ESP32 WiFi 固件 | `esp32_wifi/` | 机械手作为 WiFi 热点或 STA 设备 | ESP32-S3 + 舵机控制链路 |
| WebSocket 改造固件 | `firmware_ws/` | 多历史固件版本维护 | PlatformIO / Arduino |
| Python Bridge | `python_bridge/` | PC 侧有线桥接、自动化测试、fake 模式 | Python 3.10+；真实模式需串口 |
| C DLL | `c_dll/` | C/C++/C#/Python ctypes 等跨语言调用 | CMake；WiFi WebSocket 服务端 |
| 协议文档 | `protocol/` | JSON 控制协议说明 | 无 |

---

## Android 控制应用

Android App 支持两种控制链路：

| 模式 | 连接方式 | 说明 |
|------|----------|------|
| WiFi WebSocket | 连接 ESP32 热点或局域网 IP | 推荐无线控制方式 |
| USB OTG 串口 | 手机 USB-C OTG 直连控制板 | 不依赖 ESP32 WiFi |

主要功能：

- 7 通道紧凑控制映射到灵巧手执行器
- 15 关节状态显示与回填
- 预设动作：张开、抓握、捏取、OK、剪刀手、点赞、石头/布/剪刀等
- MediaPipe 手势识别跟随控制
- WiFi AP / STA 配置下发
- USB 串口状态读取与位置控制

调试构建由 GitHub Actions 的 `Android CI` 自动完成；正式 APK 由版本标签触发 `Android Release`。

---

## ESP32 WiFi 固件

默认 WebSocket 地址：

```text
ws://192.168.4.1:8765
```

默认 AP 配置：

| 配置 | 值 |
|------|----|
| 热点名称 | `AeroHand_WIFI` / 左右手专用热点名 |
| 热点密码 | `12345678` |
| WebSocket 端口 | `8765` |

固件支持：

- `joint_control`
- `multi_joint_control`
- `actuator_control`
- `get_states`
- `homing`
- WiFi 配置相关命令

烧录示例：

```bash
esptool.py --chip esp32s3 --port COM3 write_flash \
  0x0 firmware_bin/boot_app0.bin \
  0x1000 firmware_bin/aero_hand_wifi.ino.bootloader.bin \
  0x8000 firmware_bin/aero_hand_wifi.ino.partitions.bin \
  0x10000 firmware_bin/aero_hand_wifi.ino.merged.bin
```

---

## WebSocket 协议

### 单关节控制

```json
{
  "type": "joint_control",
  "timestamp": 1710000000000,
  "data": {
    "joint_id": "index_proximal",
    "angle": 45.0,
    "duration_ms": 500
  }
}
```

### 多关节控制

```json
{
  "type": "multi_joint_control",
  "timestamp": 1710000000000,
  "data": {
    "joints": [
      {"joint_id": "thumb_proximal", "angle": 30.0},
      {"joint_id": "index_proximal", "angle": 60.0}
    ],
    "duration_ms": 500
  }
}
```

### 执行器控制

```json
{
  "type": "actuator_control",
  "timestamp": 1710000000000,
  "data": {
    "actuators": [
      {"id": 0, "angle": 0.0},
      {"id": 1, "angle": 15.0}
    ],
    "duration_ms": 500
  }
}
```

### 状态与归零

```json
{"type": "get_states", "timestamp": 1710000000000}
{"type": "homing", "timestamp": 1710000000000}
```

完整协议见 [`protocol/CONTROL_PROTOCOL.md`](protocol/CONTROL_PROTOCOL.md)。

---

## 关节定义

| 关节 ID | 描述 | 角度范围 |
|---------|------|----------|
| `thumb_proximal` | 拇指近端 | 0° ~ 90° |
| `thumb_distal` | 拇指远端 | 0° ~ 90° |
| `index_proximal` / `index_middle` / `index_distal` | 食指三节 | 0° ~ 90° |
| `middle_proximal` / `middle_middle` / `middle_distal` | 中指三节 | 0° ~ 90° |
| `ring_proximal` / `ring_middle` / `ring_distal` | 无名指三节 | 0° ~ 90° |
| `pinky_proximal` / `pinky_middle` / `pinky_distal` | 小指三节 | 0° ~ 90° |
| `thumb_rotation` | 拇指旋转 | -30° ~ 30° |

Android 内部还使用 7 通道紧凑控制映射，最终会转换到固件侧执行器或关节目标。

---

## Python Bridge

fake 模式可不接硬件，适合协议和 App 调试：

```bash
cd python_bridge
pip install -e .
python -m aero_ws_python.server --fake
```

测试：

```bash
cd python_bridge
python -m pytest tests -q
```

Python 客户端会忽略畸形状态项，避免单个坏关节数据中断接收循环。

---

## C DLL

C DLL 提供纯 C ABI，适合 C/C++、C#、Python ctypes 等跨语言调用。

核心 API：

| API | 说明 |
|-----|------|
| `aero_ws_create` / `aero_ws_destroy` | 创建和销毁连接句柄 |
| `aero_ws_connect` / `aero_ws_disconnect` | 建立和断开 WebSocket 连接 |
| `aero_ws_set_joint` | 单关节控制 |
| `aero_ws_set_joints` | 多关节控制 |
| `aero_ws_get_states` | 同步获取状态 |
| `aero_ws_free_states` | 释放 `aero_ws_get_states` 返回的状态数组 |
| `aero_ws_homing` | 归零 |
| `aero_ws_send_raw` | 发送原始 JSON |

构建：

```bash
cd c_dll
cmake -S . -B build
cmake --build build --config Release
```

---

## 固件版本

| 版本 | 热保护 | 初始扭矩 | 特性 |
|------|--------|----------|------|
| v0.1.0 | 无 | 1023 | 基础 WebSocket 版本 |
| v0.1.3 | 50°C / 200 | 700 | 添加热保护 |
| v0.1.4 | 70°C / 500 | 700 | 放宽热保护 |
| v0.1.5 | 70°C / 500 | 700 | 电机配置修复 |
| v0.2.0 | 70°C / 500 | 700 | 拇指归位偏移优化 |

固件发布由版本标签触发 `Firmware Release` 工作流自动编译并上传产物。

---

## 稳定性修复记录

当前版本重点加固了以下风险点：

- WebSocket payload 日志不再按 C 字符串打印，避免非 NUL 结尾 payload 越界读取
- Python 客户端跳过畸形状态项，避免接收循环异常退出
- C DLL 校验 joint id、角度、duration，避免 JSON 注入、NaN 和缓冲区截断
- C DLL `get_states` 修复同步读取与后台接收线程抢 socket 的问题
- C DLL 新增 `aero_ws_free_states`，明确跨 DLL 边界内存释放方式
- C DLL 断开连接时先关闭 socket 唤醒接收线程，再等待线程退出
- Android USB 串口读写串行化，避免并发读写同一串口
- Android USB 状态帧改为累积读取 16 字节，避免短读丢帧
- Android WebSocket 旧连接回调用 token 隔离，避免覆盖新连接状态
- Android 状态解析过滤 NaN / Inf
- Android WiFi 状态回填逻辑修正为 WiFi 模式生效

---

## 本地验证

可直接运行的检查：

```bash
git diff --check
python -m pytest python_bridge/tests -q
python -m py_compile \
  python_bridge/aero_ws_python/client.py \
  python_bridge/aero_ws_python/server.py \
  python_bridge/aero_ws_python/protocol.py
```

Android 本地构建建议使用 JDK 17：

```bash
cd android
./gradlew assembleDebug
```

如果本机是 JDK 25，当前 Kotlin/Gradle 组合会在 Gradle Kotlin DSL 解析阶段失败；GitHub Actions 使用 JDK 17，是发布门禁。

---

## CI / 发布

| Workflow | 触发条件 | 产物 |
|----------|----------|------|
| Android CI | push / PR 到 `main` | Debug APK artifact |
| Android Release | 推送 `v*` 标签 | Release APK + GitHub Release |
| Firmware Release | 推送 `v*` 标签 | 固件 bin 包 + GitHub Release |

推荐发布流程：

```bash
git status
git diff --check
python -m pytest python_bridge/tests -q
git commit -m "fix: harden websocket control paths"
git push origin main
# 确认 Android CI 通过后，再打 v* 标签触发正式发布
```

---

## 目录结构

```text
aero-hand-open_websockets/
├── android/          # Android 控制应用
├── c_dll/            # C ABI WebSocket 客户端库
├── esp32_wifi/       # ESP32 WiFi WebSocket 固件
├── firmware_ws/      # 多版本 WebSocket 改造固件
├── firmware_bin/     # 预编译固件二进制
├── python_bridge/    # Python WebSocket 桥接
├── protocol/         # JSON 协议文档
├── scripts/          # 固件构建脚本
└── docs/             # 附加文档
```

---

## 许可证

- 软件代码：Apache-2.0
- 上游硬件设计文件：CC BY-NC-SA 4.0

详见 [`LICENSE.md`](LICENSE.md)。

---

## 相关链接

- 本项目：https://github.com/fanfan142/aero-hand-open_websockets
- 上游项目：https://github.com/TetherIA/aero-hand-open
