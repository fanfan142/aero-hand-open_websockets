# Aero Hand Open - Android App

Android 原生应用，用于通过 WiFi 或 USB 控制 Aero Hand Open 机械手。

## 功能特性

- **WiFi WebSocket 控制**：通过局域网连接 ESP32 WebSocket 服务器
- **USB 串口控制**：通过 USB OTG 连接串口控制
- **7 通道滑块控制**：拇指 3 维 + 四指各 1 维
- **快捷动作按钮**：Homing、All Zeros、Get States
- **实时日志显示**：查看发送/接收数据
- **浅色主题**：Material Design 3 浅色界面

## 协议映射

应用采用 SDK 风格的 7 通道控制，自动展开为 15 关节协议：

| 紧凑控制 | 展开关节 |
|----------|----------|
| 拇指外展 (0-100) | thumb_rotation (-30°-30°) |
| 拇指屈曲 (0-55) | thumb_proximal |
| 拇指肌腱 (0-90) | thumb_distal |
| 食指 (0-90) | index_proximal/middle/distal |
| 中指 (0-90) | middle_proximal/middle/distal |
| 无名指 (0-90) | ring_proximal/middle/distal |
| 小指 (0-90) | pinky_proximal/middle/distal |

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

### CI/CD 构建

推送到 main 分支会自动触发 GitHub Actions 构建，APK 会作为 Release 产物发布。

## 安装

1. 将 APK 文件传输到 Android 手机
2. 在手机设置中允许安装未知来源应用
3. 直接安装 APK 即可

## 使用方法

### WiFi 模式

1. 确保 ESP32 已烧录 WebSocket 固件并运行
2. 手机连接 ESP32 的 WiFi 热点（默认 `AeroHand_WIFI`）
3. 打开应用，选择 WiFi 模式
4. 输入 ESP32 的 IP 地址和端口（默认 `192.168.4.1:8765`）
5. 点击 Connect 连接

### USB 模式

1. 使用 USB OTG 线连接手机和 ESP32
2. 手机需要支持 USB Host 功能
3. 打开应用，选择 USB 模式
4. 点击 Connect 连接

### 控制

- 拖动滑块控制对应关节角度
- 点击 Homing 归零所有关节
- 点击 All Zeros 发送全零命令
- 点击 Get States 查询当前关节状态

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material Design 3
- **架构**：MVVM
- **WebSocket**：OkHttp 4.12.0
- **USB 串口**：usb-serial-for-android 3.8.1

## 项目结构

```
android/
├── app/
│   └── src/main/
│       ├── java/com/aerohand/
│       │   ├── ui/
│       │   │   ├── components/  # UI 组件
│       │   │   ├── screens/     # 屏幕
│       │   │   └── theme/       # 主题
│       │   ├── viewmodel/       # ViewModel
│       │   ├── websocket/       # WebSocket 服务
│       │   └── usb/            # USB 串口服务
│       └── res/                # 资源文件
├── .github/workflows/          # GitHub Actions
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/           # Gradle Wrapper
```

## License

与主项目一致，Apache-2.0
