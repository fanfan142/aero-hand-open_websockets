/**
 * Aero Hand WiFi Configuration
 * ESP32 WiFi AP + WebSocket 配置
 *
 * WiFi模式: 可以配置为AP热点模式或STA连接路由器模式
 */

#ifndef CONFIG_H
#define CONFIG_H

// ============================================
// WiFi 配置
// ============================================

// WiFi工作模式选择
// 1: AP模式 (ESP32开热点，手机/电脑直连)
// 2: STA模式 (ESP32连接已有路由器)
#define WIFI_MODE 1

// AP模式配置 (WIFI_MODE = 1)
#define AP_SSID "AeroHand_WIFI"
#define AP_PASSWORD "12345678"
#define AP_CHANNEL 6

// STA模式配置 (WIFI_MODE = 2)
#define STA_SSID "Your_WiFi_SSID"
#define STA_PASSWORD "Your_WiFi_Password"

// ============================================
// WebSocket 配置
// ============================================

#define WS_PORT 8765
#define WS_PATH "/"
#define WS_MAX_CLIENTS 4

// 指令处理间隔 (ms)
#define COMMAND_INTERVAL_MS 20

// ============================================
// 串口配置 (与舵机通信)
// ============================================

#define SERVO_SERIAL Serial1
#define SERVO_BAUDRATE 1000000

// ESP32-S3 串口引脚（对齐原始 firmware/main）
#define SERVO_TX_PIN 3
#define SERVO_RX_PIN 2

// ============================================
// 关节/舵机配置
// ============================================

// 关节数量
#define JOINT_COUNT 15
#define SERVO_COUNT 7

// 舵机角度限制 (0.1度单位)
// 例如: 900 = 90.0度
#define SERVO_MIN_ANGLE 0
#define SERVO_MAX_ANGLE 900

// 拇指旋转角度限制 (可以是负数)
#define THUMB_ROT_MIN_ANGLE -300  // -30度
#define THUMB_ROT_MAX_ANGLE 300   // 30度

// 关节ID映射
enum JointID {
    JOINT_THUMB_PROXIMAL = 0,
    JOINT_THUMB_DISTAL = 1,
    JOINT_INDEX_PROXIMAL = 2,
    JOINT_INDEX_MIDDLE = 3,
    JOINT_INDEX_DISTAL = 4,
    JOINT_MIDDLE_PROXIMAL = 5,
    JOINT_MIDDLE_MIDDLE = 6,
    JOINT_MIDDLE_DISTAL = 7,
    JOINT_RING_PROXIMAL = 8,
    JOINT_RING_MIDDLE = 9,
    JOINT_RING_DISTAL = 10,
    JOINT_PINKY_PROXIMAL = 11,
    JOINT_PINKY_MIDDLE = 12,
    JOINT_PINKY_DISTAL = 13,
    JOINT_THUMB_ROTATION = 14,
};

// ============================================
// 调试配置
// ============================================

#define ENABLE_DEBUG
#define DEBUG_BAUD_RATE 115200

#ifdef ENABLE_DEBUG
#define DEBUG_BEGIN() Serial.begin(DEBUG_BAUD_RATE)
#define DEBUG_PRINT(x) Serial.print(x)
#define DEBUG_PRINTLN(...) Serial.println(__VA_ARGS__)
#define DEBUG_PRINTF(fmt, ...) Serial.printf(fmt, ##__VA_ARGS__)
#else
#define DEBUG_BEGIN()
#define DEBUG_PRINT(x)
#define DEBUG_PRINTLN(...)
#define DEBUG_PRINTF(fmt, ...)
#endif

#endif // CONFIG_H
