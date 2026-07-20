# Aero Hand Open - Android App

Android 原生应用，用于通过 WiFi 或 USB OTG 控制 Aero Hand Open 机械手。

## 功能特性

- **WiFi WebSocket 控制**：通过局域网连接 ESP32 WebSocket 服务器
- **WiFi 配网**：校验并下发 SSID、密码、静态 IP、网关、子网和 DNS，收到设备状态确认后再切 STA
- **固件能力检测**：按 `firmware_type/protocol_version` 选择能力；未知旧固件自动降级为 `multi_joint_control`
- **USB OTG 串口控制**：支持常见 USB 串口桥接芯片，默认 921600 波特率
- **SDK 同源 7 通道控制**：拇指 3 维 + 四指各 1 维
- **预设动作库**：张开、抓握、捏取、OK、剪刀手、点赞、石头/布/剪刀、数数、扇形展开
- **快捷动作按钮**：Homing、All Zero、Get States
- **15 关节展开预览**：实时查看 compact control 到协议 joints 的映射
- **MediaPipe 手势跟随**：复用相机帧缓冲，三步标定后以独立实时链路控制机械手
- **实时日志显示**：查看发送/接收数据
- **单页高密度控制台**：更圆润、更清晰、更科技风，尽量少滑屏
- **连接面板可折叠**：顶部三角按钮可展开/收起，连接成功后自动收起
- **浅色主题**：Material Design 3 浅色界面
- **云端构建发布**：`main`、Pull Request 和版本标签均由 GitHub Actions 测试并构建

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

`assembleDebug` 可使用 Android 默认调试签名；`assembleRelease` 必须配置 `android/keystore.properties` 或对应环境变量，否则只会生成未签名产物，不能用于正式安装。

```bash
cd android
./gradlew assembleDebug
./gradlew assembleRelease
```

APK 输出位置：
- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`

### CI/CD 构建与发布

推送到 `main` 分支会自动触发 GitHub Actions：

- 运行 Python 3.8/3.11 测试和 Android JVM 单元测试
- 使用正式密钥构建非调试的签名预览 APK
- 版本名自动生成 `1.5.5-t<run_number>`
- 上传 `aero-hand-preview-apk` Actions Artifact

Pull Request 不读取仓库签名 Secrets，只构建默认调试签名的 Debug APK，用于编译回归，不用于覆盖正式安装包。

推送版本号标签（例如 `v1.5.5`）会触发正式发布工作流：

- 构建已签名 `release` APK（使用统一发布密钥）
- 自动创建对应版本 Release
- 上传 `app-release.apk`

> `main` 的签名预览包与标签 Release 使用同一密钥和同一套 `versionCode` 规则，可直接覆盖升级。Pull Request 的 Debug APK 使用另一套签名，不能覆盖正式包。

### 统一签名（避免每次安装先卸载）

要让新 APK 能覆盖安装旧版本，关键是 **始终使用同一套签名密钥**。

#### 本地开发

1. 生成并妥善保存你的发布 keystore（不要提交到仓库）
2. 复制 `android/keystore.properties.example` 为 `android/keystore.properties`
3. 填写 `storeFile/storePassword/keyAlias/keyPassword`
4. 执行 release 构建即可使用该密钥签名

`android/keystore.properties` 已加入 `.gitignore`，避免明文入库。

#### GitHub Actions

在仓库 Secrets 配置：

- `ANDROID_SIGNING_KEYSTORE_BASE64`（keystore 的 base64）
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

`main`/手动触发的 `build.yml` 与 `release.yml` 都强依赖这些 Secrets；缺失时直接失败。Pull Request 不使用 Secrets，并明确产出 Debug APK。

> 建议：团队统一维护一套 upload key，至少做离线备份 + 密码托管，避免密钥丢失导致无法升级。

### 测试版版本号规则

云端构建默认使用：

- 预览 `versionName = 1.5.5-t<github.run_number>`
- 正式版 `versionName = <tag 去掉 v 前缀>`
- `versionCode = 100000 + git rev-list --count HEAD`

Android CI 与 Android Release 会完整检出 Git 历史；同一提交得到相同 `versionCode`，后续提交单调递增，避免两个 workflow 的独立 `run_number` 导致版本倒退。

### MediaPipe 手势识别排障建议

当前实现已修复以下关键问题：

1. 校准状态不再在检测帧里自动跳步，改为仅在“记录姿势”按钮触发时推进；
2. `detectForVideo` 时间戳保持单调递增，GPU 失败时自动回退 CPU；
3. RGBA 转换限制检测边长为 256，并复用 Bitmap、ByteArray 和采样索引，避免超大相机帧转换与频繁 GC；
4. 平滑器首帧直接初始化，控制时长按检测帧周期自适应到 40-120 ms；
5. 控制帧与 Compose UI 状态分离，机械手发送不再被整屏重组阻塞，UI 控制值限制为 10 Hz；
6. 标定按左右手、前后摄和镜像上下文隔离，schema 5 会使旧标定自动失效。

若仍识别差，优先检查：

- 光照是否充足、背景是否干净；
- 手是否完整进入取景框；
- 升级后是否重新完成三步校准（张开/握拳/拇指内收）；
- 设备性能是否导致 FPS 过低（观察页面 FPS 指标）。

## 安装

1. 正式安装请优先从版本 Release 下载 `HandControl-*.apk`；`main` 分支的 Actions Artifact 用于安装前预览测试
2. 将 APK 文件传输到 Android 手机
3. 在手机设置中允许安装未知来源应用
4. 直接安装 APK 即可

## 使用方法

### WiFi 模式

1. 确保 ESP32 已烧录 `v1.5.5+` Release 中与左右手匹配的 `firmware_v0.2.0_*.bin`，或烧录与当前 App 同一提交的 Firmware CI artifact；固件须明确回传 `protocol_version >= 2` 才支持完整配网
2. 手机连接 ESP32 的 WiFi 热点（右手默认 `AeroHand_Right`，左手默认 `AeroHand_Left`）
3. 打开应用，选择 WiFi 模式
4. 输入 ESP32 的 IP 地址和端口（默认 `192.168.4.1:8765`）
5. 点击“连接 WiFi”开始控制

### AP 到 STA 静态 IP 配网

1. 手机保持连接 ESP32 默认 AP，并在 App 中连接 `192.168.4.1:8765`
2. 展开连接面板，点击“扫描 2.4G”，按系统提示授予附近 WiFi / 定位权限
3. 从扫描列表选择目标 2.4GHz SSID，输入密码；开放网络可留空
4. 填写静态 IP、网关、子网、DNS1、DNS2；App 会校验 IPv4、连续子网掩码及 IP/网关同网段
5. 点击“下发并切 STA”；App 先等待设备回传相同 SSID 与静态 IP，再发送切换指令
6. 如果 2.5 秒内未确认，设备保持当前网络，App 会报告固件过旧或参数被拒绝
7. 确认成功后让手机切到同一个 WiFi，使用 App 预填的静态 IP 重新连接

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
- `firmware_bin/` 中的历史 Arduino 镜像缺少 `actuator_control` 和 App 配网命令，不应作为当前 App 的推荐固件
- `main` 分支上传正式密钥签名的预览 APK；Pull Request 上传 Debug APK；版本标签上传正式 Release APK
- 云端构建通过只证明软件与固件可编译，AP→STA、旧固件降级、手势 FPS/GC 和舵机连续响应仍需真机验证

## License

与主项目一致，Apache-2.0
