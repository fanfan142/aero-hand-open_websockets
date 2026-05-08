# Aero Hand WiFi ESP32 固件

ESP32 WiFi 控制固件，支持 AP 热点模式和 WebSocket 通信。

## 功能特性

- WiFi AP 模式：ESP32 开启热点，手机/电脑直连控制
- WiFi STA 模式：保存路由器 SSID/密码并用静态 IP 入网
- 上电初始化后自动执行一次 homing 归位
- WebSocket 服务端：接收 JSON 指令控制机械手
- 完整协议支持：单关节/多关节控制、状态查询、归零、WiFi 配网

## 硬件要求

- ESP32-S3 开发板
- 已配置 DC-DC 降压模块（输出 5V）
- 已连接舵机控制板

## 接线

| ESP32 | 舵机控制板 |
|-------|------------|
| GPIO43 (TX) | RX |
| GPIO44 (RX) | TX |
| GND | GND |

## Arduino IDE 配置

1. 安装 ESP32 开发板支持
   - 工具 -> 开发板 -> 开发板管理器 -> 搜索 "esp32" -> 安装

2. 选择开发板
   - 工具 -> 开发板 -> ESP32 Arduino -> "XIAO_ESP32S3" 或你的具体型号

3. 安装依赖库
   - **ArduinoJson** by Benoit Blanchon（库管理器搜索安装）
   - **WebSocketsServer** - ESP32内置，无需安装额外库

4. 上传
   - 选择正确的端口
   - 上传 sketch

## 配置

在 `config.h` 中修改:

```cpp
// WiFi模式: 1=AP热点, 2=STA连接路由器
#define WIFI_MODE 1

// AP模式热点名称和密码
#define AP_SSID "AeroHand_WIFI"
#define AP_PASSWORD "12345678"

// WebSocket端口
#define WS_PORT 8765

// STA 静态网络默认值，可只改 IP，其它项保持默认
#define STA_STATIC_IP "192.168.1.210"
#define STA_GATEWAY "192.168.1.1"
#define STA_SUBNET "255.255.255.0"
#define STA_DNS1 "192.168.1.1"
#define STA_DNS2 "114.114.114.114"
```

运行后也可通过 Android App 下发并保存 STA 配置。

## 测试

固件上传后:
1. ESP32 初始化并执行一次 homing
2. ESP32 会创建 WiFi 热点 "AeroHand_WIFI"
3. 手机/电脑连接该热点 (密码: 12345678)
4. App 或浏览器连接 `192.168.4.1:8765`
5. App 可扫描 2.4GHz WiFi，填写密码和静态网络参数
6. 下发配置后 ESP32 切换到 STA，之后使用静态 IP 继续控制机械手

## 协议格式

详见 `../../protocol/CONTROL_PROTOCOL.md`
