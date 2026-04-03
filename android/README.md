# Aero Hand Open - Android App

Android 原生应用，用于通过 WiFi 或 USB OTG 控制 Aero Hand Open 机械手。

## 功能特性

- **WiFi WebSocket 控制**：通过局域网连接 ESP32 WebSocket 服务器
- **USB OTG 串口控制**：支持常见 USB 串口桥接芯片，默认 921600 波特率
- **SDK 同源 7 通道控制**：拇指 3 维 + 四指各 1 维
- **预设动作库**：张开、抓握、捏取、OK、剪刀手、点赞、石头/布/剪刀、数数、扇形展开
- **快捷动作按钮**：Homing、All Zero、Get States
- **15 关节展开预览**：实时查看 compact control 到协议 joints 的映射
- **实时日志显示**：查看发送/接收数据
- **单页高密度控制台**：更圆润、更清晰、更科技风，尽量少滑屏
- **浅色主题**：Material Design 3 浅色界面
- **自动构建发布**：推送到 `main` 后自动构建 APK，并更新 GitHub Release

## 协议映射

应用采用 SDK 风格的 7 通道控制，自动展开为 15 关节协议：

| 紧凑控制 | 展开关节 |
|----------|----------|
| 拇指外展 (0-100) | thumb_rotation (-30°~30°) |
| 拇指屈曲 (0-55) | thumb_proximal |
| 拇指肌腱 (0-90) | thumb_distal |
| 食指 (0-90) | index_proximal / middle / distal |
| 中指 (0-90) | middle_proximal / middle / distal |
| 无名指 (0-90) | ring_proximal / middle / distal |
| 小指 (0-90) | pinky_proximal / middle / distal |

USB 模式下会进一步把 7 通道控制映射为 Aero Hand SDK 同源的 16 关节/7 执行器串口帧。

## 构建

### 前置要求

- Android Studio Arctic Fox 或更高版本
- Android SDK 34
- JDK 17

### 命令行构建

```bash
cd android
./gradlew assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

### CI/CD 构建与发布

推送到 `main` 分支会自动触发 GitHub Actions：

- 构建 Debug APK
- 上传 Actions Artifact
- 自动更新 GitHub Release `latest`
- 将 APK 作为 Release 资产上传

## 安装

1. 从 GitHub Release 或 Actions Artifact 下载 APK
2. 将 APK 文件传输到 Android 手机
3. 在手机设置中允许安装未知来源应用
4. 直接安装 APK 即可

## 使用方法

### WiFi 模式

1. 确保 ESP32 已烧录 WebSocket 固件并运行
2. 手机连接 ESP32 的 WiFi 热点（默认 `AeroHand_WIFI`）
3. 打开应用，选择 WiFi 模式
4. 输入 ESP32 的 IP 地址和端口（默认 `192.168.4.1:8765`）
5. 点击“连接 WiFi”开始控制

### USB 模式

1. 使用 OTG 转接头将手机连接到控制板 USB 串口
2. 打开应用，选择 `USB OTG`
3. 点击“连接 USB”
4. 首次连接时确认系统 USB 授权弹窗
5. 成功后即可使用滑块、预设动作和快捷指令控制

当前代码内优先匹配以下常见 USB 串口芯片厂商：

- FTDI
- Silicon Labs / CP210x
- CH340 / WCH

### 控制

- 拖动滑块控制对应关节角度
- 点击预设动作执行一键手势/动作
- 点击 `Homing` 执行归位
- 点击 `All Zero` 发送全零状态
- 点击 `Get States` 查询当前状态
- 通过协议预览和日志窗口查看实时控制数据

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material Design 3
- **架构**：MVVM + StateFlow
- **WebSocket**：OkHttp 4.12.0
- **USB**：usb-serial-for-android 3.9.0
- **CI/CD**：GitHub Actions + Release 自动上传

## 项目结构

```text
android/
├── app/
│   └── src/main/
│       ├── java/com/aerohand/
│       │   ├── ui/
│       │   │   ├── components/  # UI 组件
│       │   │   ├── screens/     # 屏幕
│       │   │   └── theme/       # 主题
│       │   ├── viewmodel/       # 状态与控制编排
│       │   ├── websocket/       # WebSocket 协议与服务
│       │   └── usb/             # USB OTG 串口服务
│       └── res/                 # 资源文件
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/              # Gradle Wrapper
```

## 注意事项

- WiFi 模式当前使用明文 `ws://` 连接，适合局域网设备控制场景
- USB 模式依赖 Android 手机支持 OTG
- 当前 Release 上传的是 Debug APK，便于快速验证功能

## License

与主项目一致，Apache-2.0
