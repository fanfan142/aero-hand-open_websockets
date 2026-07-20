# v0.2.0 固件与 Android APP 调用兼容性审计

## 2026-07-20 补充审计

本节覆盖并取代下方以 `v1.5.3/v1.5.4` 为基线的历史发布结论；旧段落保留用于追溯。

- 当前开发版本：Android `1.5.5`，最终 Android 与固件编译只以 GitHub Actions 为准，本轮未执行本地 Gradle/PlatformIO 编译。
- `firmware_bin/aero_hand_wifi.ino.bin` 与 merged 镜像已通过静态二进制契约检查确认缺少 `actuator_control`、能力握手和全部 App 配网命令，只能视为历史产物。
- 推荐烧录入口：`v1.5.5+` GitHub Release 的 `firmware_v0.2.0_lefthand.bin` / `firmware_v0.2.0_righthand.bin`，或与当前 App 同一提交的 Firmware CI artifact，地址 `0x10000`。不能只按 v0.2.0 文件名判定配网兼容性。
- App 按 `firmware_type + protocol_version` 判定能力：`firmware_ws v0.1.x` 和未声明协议版本的旧 v0.2.0 可执行器控制但禁用关联静态 IP 配网；本轮 v0.2.0 与当前 `esp32_wifi` 源码声明 `protocol_version=2` 后启用；未知旧固件降级 `multi_joint_control`。
- WiFi 配置、切 STA、切 AP、清配置都使用 `request_id`；推荐固件 ACK 回传 `command_type/request_id`，配置状态还回传相同 `request_id`。App 不再把 `WebSocket.send()` 入队成功当作执行成功。
- 固件契约门禁检查能力握手、控制、状态、配网及 ACK 关联标记；Firmware CI 运行 checker 测试，云端编译独立固件和 v0.2.0 左右手，并对生成 bin 再做契约检查。
- 手势控制帧携带单调时间戳；相机 generation 会丢弃切镜头/停止后的旧帧，标定样本、平滑器与 tracker 生命周期在同一互斥边界内处理。

当前静态验证不等于真机通过。云端变绿后仍需验证 AP→STA、旧固件降级、左右手重新标定、FPS/GC 和连续舵机响应。

审计日期：2026-06-15

## 范围

- Android APP：审计基线为 `main` / `v1.5.3`，HEAD `0928802784cd7893c9d0fe978e85a416e2ec4c58`。
- 发布固件：审计基线为 GitHub Release `v1.5.3` 中的 `firmware_v0.2.0_lefthand.bin`、`firmware_v0.2.0_righthand.bin`。
- 源码入口：`firmware_ws/v0.2.0/firmware.ino`。
- 说明：用户口径中的 `0.20` 在仓库中对应 `v0.2.0`。
- 本段记录的是历史 `v1.5.4` 发布计划；当前修复与推荐入口以页首 `v1.5.5+` 结论为准。

## 发布链路确认

| 项 | 结论 | 证据 |
|---|---|---|
| 最新 Release | `v1.5.3`，发布时间 `2026-06-11T04:09:06Z` | GitHub API `/releases/latest` |
| Release 包含 v0.2.0 左/右手固件 | 是，两个 bin 均存在 | GitHub API release assets |
| 本地 HEAD 与远端 main | 一致，均为 `0928802` | `git rev-parse HEAD` / `origin/main` |
| 发布构建入口 | 使用 `scripts/build_versions.py` 构建 `firmware_ws/v0.2.0` 顶层文件 | `.github/workflows/firmware_release.yml:27`、`scripts/build_versions.py:131` |
| 未跟踪嵌套固件目录 | 不进入发布脚本；本轮已同步为与顶层入口一致，降低人工误烧录风险 | `scripts/build_versions.py:133` 只复制顶层文件；两个 `firmware.ino` SHA256 当前一致 |

## APP 调用面

Android WiFi 控制只通过 `WebSocketService` 发送 JSON，命令生成集中在 `Commands` 和 payload builder：

| APP 功能 | APP 命令 | APP 证据 |
|---|---|---|
| 连接后自动请求状态 | `get_states`、`wifi_status` | `WebSocketService.kt:61`、`WebSocketService.kt:65` |
| 手动归位 | `homing` | `HandControlViewModel.kt:348`、`WebSocketService.kt:109`、`Protocol.kt:806` |
| 滑杆控制 | `actuator_control` | `HandControlViewModel.kt:335`、`HandControlViewModel.kt:608`、`Protocol.kt:861` |
| 全零 | `actuator_control` | `HandControlViewModel.kt:355`、`HandControlViewModel.kt:542` |
| 预设动作 | `actuator_control` | `HandControlViewModel.kt:474`、`HandControlViewModel.kt:546`、`HandControlViewModel.kt:608` |
| 宏动作 | `actuator_control` | `HandControlViewModel.kt:506`、`HandControlViewModel.kt:546` |
| 手势跟随 | `actuator_control` | `HandControlViewModel.kt:722`、`HandControlViewModel.kt:743` |
| 状态回填 | 解析 `states_response` | `WebSocketService.kt:80`、`Protocol.kt:1122` |
| WiFi 状态显示 | 解析 `wifi_status` | `WebSocketService.kt:79`、`Protocol.kt:1164` |
| AP 到 STA 配网 | `wifi_config_set` + `wifi_connect_sta` | `HandControlViewModel.kt:374`、`Protocol.kt:812`、`Protocol.kt:834` |
| 切回 AP | `wifi_start_ap` | `HandControlViewModel.kt:435`、`Protocol.kt:836` |
| 清除 STA | `wifi_clear_sta` | `HandControlViewModel.kt:455`、`Protocol.kt:838` |
| 兼容多关节协议 | `multi_joint_control` builder 保留 | `Protocol.kt:878`、`ProtocolTest.kt:105` |

## 四个重点功能链路结论

| 重点 | APP 链路 | 固件链路 | 当前结论 |
|---|---|---|---|
| WiFi / LAN WebSocket 连接与解析 | `HandControlViewModel.connect()` 校验 Host/Port 后调用 `WebSocketService.connect()`；连接成功后自动发送 `get_states` 和 `wifi_status`；`onMessage()` 解析 `hand_info`、`wifi_status`、`states_response` | `setupWiFi()` 按 AP/STA/Dual 启动网络；`wsServer.begin(8765)` 监听；连接后发送 `hand_info`；文本消息进入 `handleWsCommand()` / `processJsonCommand()` 按 `type` 分发 | 本地源码闭合，支持 AP 直连和 STA 局域网 Android WebSocket 访问 |
| 常规控制 | 滑杆、全零、预设、宏最终都进入 `sendState()`；WiFi 模式默认构造 7 执行器 `actuator_control` | `actuator_control` 校验 `actuators` 数组，按 id 0..6 限幅写入目标；`applyPendingWsTarget()` 写 7 个舵机 | 本地源码闭合，7 通道常规控制支持 APP 当前全部入口 |
| 信息反馈 | APP 解析 `hand_info` 显示左右手；解析 `wifi_status` 更新模式/IP/STA 配置；解析 `states_response` 回填控制值 | 固件连接时发送 `hand_info`；`wifi_status` 返回模式、当前 IP、STA/DNS 配置；`get_states` 返回 15 关节状态 | 本地源码闭合；状态回填缺口已修复并有单元测试覆盖 |
| 视频手势识别与重映射跟随 | `GestureCameraService` 使用 MediaPipe 检测 21 点，估算角度、平滑、三步校准重映射、微调后输出 `GestureControlFrame`；`HandControlScreen` 收集 control frame；`HandControlViewModel.updateControlValuesFromGesture()` 以 16ms 节流、24ms duration 发送 `actuator_control` | 固件不需要理解“手势”概念，只需稳定接收高频 `actuator_control`；队列保护在 APP，固件 20ms 写舵机节流，写入路径与常规控制相同 | 本地源码闭合；新增测试确认手势跟随 payload 使用实时 `actuator_control` 且 7 执行器角度在固件限幅内 |

## 固件命令处理逐项结论

| 命令 | 固件处理 | 结论 |
|---|---|---|
| `actuator_control` | 校验 `data.actuators`，按 `id` 0..6 写入 `g_targetActuatorAngles`，按 `ACTUATION_LOWER_LIMITS/UPPER_LIMITS` 限幅，`loop()` 中 `applyPendingWsTarget()` 写 7 个舵机 | 控制调用可用 |
| `multi_joint_control` | 校验 `data.joints`，按 `JOINT_NAMES` 映射 15 关节，限幅后转换为 7 舵机目标 | 兼容调用可用 |
| `joint_control` | 单关节映射、限幅、进入同一舵机目标转换链路 | 协议文档调用可用 |
| `get_states` | 返回 `g_jointAngles` 组成的 15 关节 `states_response`；本地修复后 `actuator_control` 写入时会维护等效关节状态 | 本地源码可用；GitHub Release `v1.5.3` 已发布 bin 仍需重新构建发布 |
| `homing` | 检查控制源与 `HOMING_isBusy()`，启动 `HOMING_start()` 并清零内部目标 | 可用 |
| `wifi_status` | 返回模式、当前 IP、STA 配置、DNS、configured mode | 可用 |
| `wifi_config_set` | 保存 SSID、密码、静态 IP、网关、子网、DNS 到 NVS | 可用 |
| `wifi_connect_sta` | 先响应，再延迟调度 STA 切换，避免响应被断网打断 | 可用 |
| `wifi_start_ap` | 先响应，再延迟调度 AP 切换 | 可用 |
| `wifi_clear_sta` | 先响应，再延迟清配置并回 AP | 可用 |

关键固件证据：

- 命令分发：`firmware_ws/v0.2.0/firmware.ino:1294`
- `actuator_control`：`firmware_ws/v0.2.0/firmware.ino:1328`
- `multi_joint_control`：`firmware_ws/v0.2.0/firmware.ino:1365`
- `get_states`：`firmware_ws/v0.2.0/firmware.ino:1403`、`firmware_ws/v0.2.0/firmware.ino:1512`
- WiFi 延迟切换：`firmware_ws/v0.2.0/firmware.ino:1190`、`firmware_ws/v0.2.0/firmware.ino:1195`、`firmware_ws/v0.2.0/firmware.ino:1576`
- 舵机写入：`firmware_ws/v0.2.0/firmware.ino:529`、`firmware_ws/v0.2.0/firmware.ino:1577`

## 已修复问题

### 1. 默认控制路径后的状态回填不准确

APP 默认发送 `actuator_control`，而固件在该路径中会将 15 个关节角 `g_jointAngles` 和 `g_targetJointAngles` 清零；`get_states` 返回的正是 `g_jointAngles`。

原影响：

- 滑杆、预设、宏动作、手势跟随本身能控制 7 个执行器。
- 用户点击获取状态或连接后自动状态回填时，APP 可能把当前控制值回填成全零。
- README 中“15 关节状态显示与回填”不能用当前证据证明在默认 APP 路径下正常。

修复：

- 新增 `updateJointAnglesFromActuatorAngles()`，从 7 个执行器角度反算 15 个等效关节角。
- `actuator_control` 解析后更新 `g_targetJointAngles`，不再清零关节状态。
- `applyPendingWsTarget()` 写入执行器目标后同步更新 `g_jointAngles`，使 `get_states` 返回可被 APP 回填的状态。
- Android 单元测试新增 `statesResponseFromActuatorEquivalentState_roundTripsToCompactState()`，覆盖固件等效状态到 APP 7 通道状态的回填。

修复证据：

- 固件反算函数：`firmware_ws/v0.2.0/firmware.ino:434`
- 执行器写入后同步状态：`firmware_ws/v0.2.0/firmware.ino:583`
- `actuator_control` 不再清零关节状态：`firmware_ws/v0.2.0/firmware.ino:1359`
- Android 回填测试：`android/app/src/test/java/com/aerohand/websocket/ProtocolTest.kt:38`

原始问题证据：

- APP 默认构造 `actuator_control`：`Protocol.kt:861`
- 状态响应读取 `g_jointAngles`：`firmware_ws/v0.2.0/firmware.ino:1512`
- APP 收到状态后回填 UI：`HandControlViewModel.kt:166`

## 语义说明

### 1. `duration_ms` 是 APP 节奏参数

APP 在 `actuator_control` 和 `multi_joint_control` 中写入 `duration_ms`。v0.2.0 固件控制处理没有读取该字段；关节路径使用固定 20ms 写入周期和 `WS_SMOOTHING_ALPHA=0.35`，执行器路径直接写目标。

影响：

- 调用不会失败，APP 预设、宏动作、手势跟随的节奏仍由 APP 侧 `delay(step.durationMs)` 和发送周期控制。
- 当前 APP 功能正常调用不依赖固件端读取 `duration_ms`。
- 若后续要把协议语义升级为“固件严格按 `duration_ms` 插值执行”，需要另行实现固件端运动规划。

证据：

- APP 写入 `duration_ms`：`Protocol.kt:861`、`Protocol.kt:878`
- 固件命令处理未访问 `duration_ms`：`firmware_ws/v0.2.0/firmware.ino:1294`
- 固件固定写入节流：`firmware_ws/v0.2.0/firmware.ino:529`

### 2. 未跟踪嵌套固件目录已同步

`firmware_ws/v0.2.0/firmware/firmware.ino` 原先与发布入口 `firmware_ws/v0.2.0/firmware.ino` 不一致。发布脚本不使用该嵌套目录，但人工用 Arduino IDE 打开时可能选错入口。

处理：

- 已将嵌套入口同步为顶层发布入口的完整内容。
- 两个文件 SHA256 当前一致。
- 对 GitHub Release 构建链路无行为变化，因为发布脚本仍只复制 `firmware_ws/v0.2.0` 顶层文件。

## 当前结论

对 GitHub Release `v1.5.3` 已发布的 `firmware_v0.2.0_*.bin`，不能严谨地确认“满足 APP 所有功能正常调用”，因为默认 `actuator_control` 后状态回填缺口是在本轮本地修复的，尚未重新发布。

对当前本地源码，结论是：

- 连接、7 执行器控制、预设、宏动作、手势跟随、归位、WiFi 状态、AP/STA 配网、切回 AP、清除 STA 的命令调用面均已在源码中找到固件处理路径。
- 默认控制路径下的状态回填逻辑已在本地源码修复，并通过 Android 单元测试覆盖回填语义。
- `duration_ms` 目前是 APP 侧节奏参数，不是固件端执行时长参数；这不阻断当前 APP 功能调用。
- `firmware_ws/v0.2.0/firmware/firmware.ino` 已同步到顶层发布入口，人工烧录入口不一致风险已消除。
- 本地源码和构建层面可以支持 APP 所有 WebSocket 功能调用；真实硬件刷写后的端到端实测仍未执行。

## 已执行验证

| 验证项 | 结果 | 说明 |
|---|---|---|
| Android 单元测试 | 通过 | `./gradlew.bat :app:testDebugUnitTest --no-daemon`，24 个 Gradle task，BUILD SUCCESSFUL |
| Android Debug APK | 通过 | `./gradlew.bat :app:assembleDebug --no-daemon`，37 个 Gradle task，BUILD SUCCESSFUL |
| v0.2.0 左手固件编译 | 通过 | `pio run`，RAM 14.3%，Flash 22.9%，固件大小 766869 bytes |
| v0.2.0 右手固件编译 | 通过 | `pio run`，RAM 14.3%，Flash 22.9%，固件大小 766869 bytes |
| Release 构建脚本 | 通过 | `python scripts/build_versions.py`，10/10 固件构建成功 |
| Release v0.2.0 左手 bin | 生成成功 | `firmware_ws_build/firmware_v0.2.0_lefthand.bin`，767232 bytes |
| Release v0.2.0 右手 bin | 生成成功 | `firmware_ws_build/firmware_v0.2.0_righthand.bin`，767232 bytes |
| v0.2.0 源码入口一致性 | 通过 | 顶层入口、嵌套 Arduino 入口、左右手构建副本 SHA256 一致 |
| v0.2.0 编译警告筛查 | 通过 | 左/右手 `pio run 2>&1 | Select-String "warning|error"` 无输出 |

## 建议修复顺序

1. 将本地修复纳入提交并触发固件 Release 重新构建，生成新的 `firmware_v0.2.0_lefthand.bin` / `firmware_v0.2.0_righthand.bin`。
2. 明确 `duration_ms` 语义：若固件无需执行时长，协议文档应说明由 APP 节奏控制；若需要，则在固件端实现插值/速度策略。
3. 刷写左右手硬件后执行端到端实测：连接、归位、滑杆、全零、预设、宏、手势跟随、状态回填、AP/STA 配网、切回 AP、清除 STA。
