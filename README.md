# Aero Hand Open WebSocket 控制套件

https://github.com/user-attachments/assets/b9ed0a9f-7e19-4603-96f7-cb8995c56a5b

<p align="center">
  <img src="https://img.shields.io/badge/project-Aero%20Hand%20Open-blue" alt="project">
  <img src="https://img.shields.io/badge/android-Compose-brightgreen" alt="android">
  <img src="https://img.shields.io/badge/esp32--s3-WebSocket-orange" alt="esp32">
  <img src="https://img.shields.io/badge/license-Apache--2.0-green" alt="license">
</p>

本仓库是 Aero Hand Open 灵巧手的 WebSocket 通信扩展，提供 Android 控制 App、ESP32 WiFi 固件、Python Bridge、C DLL 和统一 JSON 协议。目标是让灵巧手可以通过手机、电脑脚本或跨语言程序稳定控制。

```text
Android / PC / 脚本
        │
        ├── WiFi WebSocket ──> ESP32-S3 ──> 舵机总线 ──> Aero Hand
        │
        ├── USB OTG 串口 ───> 原始控制板 ──> 舵机总线 ──> Aero Hand
        │
        └── Python Bridge / C DLL ──> WebSocket / USB ──> Aero Hand
```

---

## 当前发布

最新正式包在 GitHub Releases 下载：

- Android APK：`HandControl-*.apk`
- WebSocket 固件：`firmware_v*_lefthand.bin` / `firmware_v*_righthand.bin`
- 调试包：`main` 分支推送后由 Android CI 上传 artifact

发布标签会同时触发 Android Release 和 Firmware Release，同一个 Release 页面会包含 APK 与固件 bin。

---

## 该选哪条链路

| 链路 | 适合场景 | 需要什么 |
|------|----------|----------|
| Android + WiFi | 手机无线控制、预设动作、手势跟随、AP→STA 配网 | ESP32-S3 WiFi 固件 |
| Android + USB OTG | 手机直连控制板，不依赖路由器 | 支持 OTG 的手机和 USB 串口 |
| ESP32 WiFi | 上位机或脚本直接通过 WebSocket 控制 | ESP32-S3 + 舵机控制链路 |
| Python Bridge | PC 有线桥接、协议调试、fake 模式测试 | Python 3.10+，真实模式需串口 |
| C DLL | C/C++/C#/ctypes 等跨语言集成 | 可连接到 ESP32 WebSocket 服务端 |

---

## Android 控制 App

主要功能：

- WiFi WebSocket / USB OTG 双连接模式
- 7 通道紧凑控制映射到灵巧手执行器
- 15 关节状态显示与回填
- 预设动作：张开、抓握、捏取、OK、剪刀手、点赞、石头/布/剪刀等
- MediaPipe 手势识别跟随控制
- 扫描附近 2.4GHz WiFi 并下发 STA 配置
- 静态 IP、网关、子网、DNS 可自定义，默认值可自动补全

安装流程：

1. 在 Releases 下载 `HandControl-*.apk`。
2. Android 允许安装未知来源应用。
3. WiFi 模式下连接 ESP32 AP 或同网段静态 IP。
4. USB 模式下插入 OTG 串口线并授权系统弹窗。

WiFi 扫描需要系统 WiFi 已开启，并授予附近 WiFi / 定位权限。Android 10+ 对扫描频率有限制，短时间连续扫描可能返回系统缓存结果。

---

## ESP32 WiFi 固件

默认 AP：

| 配置 | 默认值 |
|------|--------|
| SSID | `AeroHand_WIFI` 或左右手专用热点名 |
| 密码 | `12345678` |
| WebSocket | `ws://192.168.4.1:8765` |

支持命令：

- `joint_control`
- `multi_joint_control`
- `actuator_control`
- `get_states`
- `homing`
- `wifi_status`
- `wifi_config_set`
- `wifi_connect_sta`
- `wifi_start_ap`
- `wifi_clear_sta`

上电后固件会执行一次归位流程；确认机械结构安全、电源稳定、手指无外部阻挡后再上电。

---

## AP → STA 静态 IP 配网

目标流程：先由 ESP32 发 AP 让手机直连配置，再切到实验室/家庭路由器，ESP32 以固定 IP 作为局域网设备。

1. 烧录并上电，等待归位完成。
2. 手机连接 ESP32 热点。
3. App WiFi 模式连接 `192.168.4.1:8765`。
4. 展开连接面板，扫描附近 2.4GHz WiFi。
5. 选择 SSID，输入密码。
6. 填写静态 IP；网关、子网、DNS 可保留默认值。
7. 点击“下发并切 STA”。
8. 手机切到目标 WiFi。
9. App 会把 Host 预填成目标静态 IP，重新连接即可控制。

默认静态网络参数：

| 字段 | 默认值 |
|------|--------|
| 静态 IP | `192.168.1.210` |
| 网关 | `192.168.1.1` |
| 子网 | `255.255.255.0` |
| DNS1 | `192.168.1.1` |
| DNS2 | `114.114.114.114` |

---

## 烧录固件

Releases 中的 `firmware_v*_*.bin` 是应用固件，烧录地址为 `0x10000`：

```bash
esptool.py --chip esp32s3 --port COM3 write_flash \
  0x10000 firmware_v0.2.0_lefthand.bin
```

如果使用 `firmware_bin/` 中的 Arduino 构建产物，可选择二者之一：

```bash
# 单文件完整镜像
esptool.py --chip esp32s3 --port COM3 write_flash \
  0x0 firmware_bin/aero_hand_wifi.ino.merged.bin

# 分区烧录
esptool.py --chip esp32s3 --port COM3 write_flash \
  0x0 firmware_bin/boot_app0.bin \
  0x1000 firmware_bin/aero_hand_wifi.ino.bootloader.bin \
  0x8000 firmware_bin/aero_hand_wifi.ino.partitions.bin \
  0x10000 firmware_bin/aero_hand_wifi.ino.bin
```

左右手固件要与实际机械手匹配，避免手型配置和舵机方向不一致。

---

## WebSocket 协议最小示例

单关节控制：

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

执行器控制：

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

状态与归位：

```json
{"type": "get_states", "timestamp": 1710000000000}
{"type": "homing", "timestamp": 1710000000000}
```

完整协议见 [`protocol/CONTROL_PROTOCOL.md`](protocol/CONTROL_PROTOCOL.md)。

---

## Python Bridge

fake 模式不需要硬件，适合协议和 App 调试：

```bash
cd python_bridge
pip install -e .
python -m aero_ws_python.server --fake
```

运行测试：

```bash
cd python_bridge
python -m pytest tests -q
```

---

## C DLL

C DLL 提供纯 C ABI，可被 C/C++、C#、Python ctypes 等调用。

常用 API：

| API | 说明 |
|-----|------|
| `aero_ws_create` / `aero_ws_destroy` | 创建和销毁连接句柄 |
| `aero_ws_connect` / `aero_ws_disconnect` | 建立和断开 WebSocket 连接 |
| `aero_ws_set_joint` | 单关节控制 |
| `aero_ws_set_joints` | 多关节控制 |
| `aero_ws_get_states` | 同步获取状态 |
| `aero_ws_free_states` | 释放状态数组 |
| `aero_ws_homing` | 归位 |
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

---

## CI / Release

| Workflow | 触发条件 | 产物 |
|----------|----------|------|
| Android CI | push / PR 到 `main` | Debug APK artifact |
| Android Release | 推送 `v*` 标签 | Release APK |
| Firmware Release | 推送 `v*` 标签 | 多版本左右手固件 bin |

本项目验证以 GitHub Actions 云端构建为准；本地 Windows 环境不作为 Android 发布门禁。

---

## 目录结构

```text
aero-hand-open_websockets/
├── android/          # Android 控制应用
├── c_dll/            # C ABI WebSocket 客户端库
├── esp32_wifi/       # ESP32 WiFi WebSocket 固件
├── firmware_ws/      # 多版本 WebSocket 改造固件
├── firmware_bin/     # Arduino 固件烧录辅助产物
├── python_bridge/    # Python WebSocket 桥接
├── protocol/         # JSON 协议文档
├── scripts/          # 固件构建脚本
└── docs/             # 附加文档
```

---

## 已审计的关键风险点

当前版本已重点处理：

- Android WiFi 扫描等待系统扫描结果事件，避免总是读取旧缓存。
- Android 不再尝试主动打开系统 WiFi，改为提示用户手动开启。
- AP→STA 配网后自动预填目标静态 IP，减少切网后的重连误操作。
- Debug CI 不依赖签名 secrets，Release 才强制签名。
- Firmware Release 文档明确应用固件烧录地址为 `0x10000`。
- WebSocket payload、C DLL 输入校验、USB 串口短读、旧 WebSocket 回调覆盖等问题已在前序版本加固。

---

## 许可证

- 软件代码：Apache-2.0
- 上游硬件设计文件：CC BY-NC-SA 4.0

详见 [`LICENSE.md`](LICENSE.md)。

---

## 相关链接

- 本项目：https://github.com/fanfan142/aero-hand-open_websockets
- 上游项目：https://github.com/TetherIA/aero-hand-open
