#!/usr/bin/env python3
"""
生成各版本WebSocket固件
将原始串口固件转换为WiFi/WebSocket版本
"""

import os
import re

# 版本配置
VERSIONS = {
    'v0.1.0': {
        'torque': '{1023,1023,1023,1023,1023,1023,1023}',
        'temp_cutoff': None,  # 无热保护
        'hot_torque': None,
        'has_auto_homing': False,
        'has_esp_system': False,
    },
    'v0.1.3': {
        'torque': '{700,700,700,700,700,700,700}',
        'temp_cutoff': '50',  # °C
        'hot_torque': '200',
        'has_auto_homing': True,
        'has_esp_system': False,
    },
    'v0.1.4': {
        'torque': '{700,700,700,700,700,700,700}',
        'temp_cutoff': '70',
        'hot_torque': '500',
        'has_auto_homing': False,
        'has_esp_system': True,
    },
    'v0.1.5': {
        'torque': '{700,700,700,700,700,700,700}',
        'temp_cutoff': '70',
        'hot_torque': '500',
        'has_auto_homing': False,
        'has_esp_system': True,
    },
    'v0.2.0': {
        'torque': '{700,700,700,700,700,700,700}',
        'temp_cutoff': '70',
        'hot_torque': '500',
        'has_auto_homing': False,
        'has_esp_system': True,
    },
}

WS_HEADER = '''// ============================================
// WiFi / WebSocket 相关头文件 (新增)
// ============================================
#include <WiFi.h>
#include <WebSocketsServer.h>
#include <ArduinoJson.h>

#include "webSocketServer.h"
'''

WS_GLOBALS = '''// ============================================
// WiFi / WebSocket 全局变量 (新增)
// ============================================
static AeroWebSocketServer wsServer;
static bool g_wsConnected = false;
static uint32_t g_lastWsActivity = 0;

// 当前关节角度记录 (用于get_states命令)
static float g_jointAngles[JOINT_COUNT] = {0};

// LED引脚
#ifdef LED_BUILTIN
#define STATUS_LED LED_BUILTIN
#else
#define STATUS_LED 48
#endif
'''

WS_FUNCTIONS_DECL = '''// ============================================
// 函数声明 (新增和原有)
// ============================================

// WebSocket相关函数 (新增)
void setupWiFi();
void wsEventHandler(uint8_t num, WStype_t type, uint8_t* payload, size_t length);
void handleWsCommand(const char* payload, size_t length);
void processJsonCommand(const JsonDocument& doc);
void sendWsResponse(bool success, const char* message);
void broadcastJointStates();
void blinkLED(int times);

// 关节名称映射 (新增)
static const char* const JOINT_NAMES[JOINT_COUNT] = {
    "thumb_proximal", "thumb_distal", "index_proximal", "index_middle", "index_distal",
    "middle_proximal", "middle_middle", "middle_distal", "ring_proximal", "ring_middle", "ring_distal",
    "pinky_proximal", "pinky_middle", "pinky_distal", "thumb_rotation"
};
uint8_t getJointNumber(const char* jointId);
'''

WS_WIFI_SETUP = '''// ============================================
// WiFi / WebSocket 初始化 (新增)
// ============================================

void setupWiFi() {
#if WIFI_MODE == 1
    // AP模式 - ESP32开热点
    DEBUG_PRINTF("[WIFI] Starting AP mode: %s\\n", AP_SSID);

    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASSWORD, AP_CHANNEL);

    IPAddress IP = WiFi.softAPIP();
    DEBUG_PRINT("[WIFI] AP IP address: ");
    DEBUG_PRINTLN(IP.toString());

#elif WIFI_MODE == 2
    // STA模式 - 连接路由器
    DEBUG_PRINTF("[WIFI] Connecting to: %s\\n", STA_SSID);

    WiFi.mode(WIFI_STA);
    WiFi.begin(STA_SSID, STA_PASSWORD);

    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 30) {
        delay(500);
        DEBUG_PRINT(".");
        attempts++;
    }

    if (WiFi.status() == WL_CONNECTED) {
        DEBUG_PRINTLN();
        DEBUG_PRINT("[WIFI] Connected! IP: ");
        DEBUG_PRINTLN(WiFi.localIP());
    } else {
        DEBUG_PRINTLN();
        DEBUG_PRINTLN("[WIFI] Connection failed, starting AP as fallback");
        WiFi.mode(WIFI_AP);
        WiFi.softAP(AP_SSID, AP_PASSWORD);
    }

#endif
}
'''

WS_EVENT_HANDLER = '''// WebSocket事件处理
void wsEventHandler(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
    switch (type) {
        case WStype_DISCONNECTED:
            DEBUG_PRINTF("[WS] Client %u disconnected\\n", num);
            g_wsConnected = false;
            break;

        case WStype_CONNECTED:
            DEBUG_PRINTF("[WS] Client %u connected\\n", num);
            g_wsConnected = true;
            blinkLED(2);
            break;

        case WStype_TEXT:
            DEBUG_PRINTF("[WS] Client %u sent: %s\\n", num, payload);
            handleWsCommand((const char*)payload, length);
            g_lastWsActivity = millis();
            break;

        default:
            break;
    }
}
'''

WS_CMD_FUNCS = '''// 根据关节名称获取编号
uint8_t getJointNumber(const char* jointId) {
    for (uint8_t i = 0; i < JOINT_COUNT; i++) {
        if (strcmp(jointId, JOINT_NAMES[i]) == 0) {
            return i;
        }
    }
    return 255; // 无效ID
}

// LED闪烁
void blinkLED(int times) {
    for (int i = 0; i < times; i++) {
        digitalWrite(STATUS_LED, LOW);
        delay(100);
        digitalWrite(STATUS_LED, HIGH);
        delay(100);
    }
}

// ============================================
// WebSocket命令处理 (新增)
// ============================================

void handleWsCommand(const char* payload, size_t length) {
    DynamicJsonDocument doc(1024);

    DeserializationError error = deserializeJson(doc, payload, length);
    if (error) {
        DEBUG_PRINTF("[CMD] JSON parse error: %s\\n", error.c_str());
        sendWsResponse(false, error.c_str());
        return;
    }

    processJsonCommand(doc);
}

void processJsonCommand(const JsonDocument& doc) {
    // 检查type字段
    if (!doc["type"].is<const char*>()) {
        sendWsResponse(false, "Missing type field");
        return;
    }

    const char* type = doc["type"].as<const char*>();

    if (strcmp(type, "joint_control") == 0) {
        // 单关节控制
        if (!doc["data"]["joint_id"].is<const char*>() || !doc["data"]["angle"].is<float>()) {
            sendWsResponse(false, "Missing required fields in joint_control");
            return;
        }

        const char* jointId = doc["data"]["joint_id"].as<const char*>();
        float angle = doc["data"]["angle"].as<float>();
        int duration = doc["data"]["duration_ms"].as<int>();

        uint8_t jointNum = getJointNumber(jointId);
        if (jointNum >= JOINT_COUNT) {
            sendWsResponse(false, "Invalid joint_id");
            return;
        }

        // 角度限制
        float minAngle = (jointNum == JOINT_THUMB_ROTATION) ? THUMB_ROT_MIN_ANGLE / 10.0f : SERVO_MIN_ANGLE / 10.0f;
        float maxAngle = (jointNum == JOINT_THUMB_ROTATION) ? THUMB_ROT_MAX_ANGLE / 10.0f : SERVO_MAX_ANGLE / 10.0f;
        float clampedAngle = constrain(angle, minAngle, maxAngle);
        int16_t angleInt = (int16_t)(clampedAngle * 10.0f);

        // 设置位置模式
        if (g_currentMode != MODE_POS) {
            if (gBusMux) xSemaphoreTake(gBusMux, portMAX_DELAY);
            for (int i = 0; i < 7; ++i) {
                hlscl.ServoMode(SERVO_IDS[i]);
            }
            g_currentMode = MODE_POS;
            if (gBusMux) xSemaphoreGive(gBusMux);
        }

        // 计算舵机目标位置
        int16_t pos[7];
        uint16_t torque_eff[7];
        for (int i = 0; i < 7; ++i) {
            pos[i] = mapU16ToRaw(i, (uint16_t)((clampedAngle / 90.0) * 4095));
            torque_eff[i] = g_torque[i];
            // 热保护 (如果启用)
            if (isHot((uint8_t)i)) {
                torque_eff[i] = u16_min(torque_eff[i], HOT_TORQUE_LIMIT);
            }
        }

        if (gBusMux) xSemaphoreTake(gBusMux, portMAX_DELAY);
        hlscl.SyncWritePosEx((uint8_t*)SERVO_IDS, 7, pos, g_speed, g_accel, torque_eff);
        if (gBusMux) xSemaphoreGive(gBusMux);

        g_jointAngles[jointNum] = clampedAngle;
        DEBUG_PRINTF("[CMD] Joint %s -> %.1f°\\n", jointId, clampedAngle);
        sendWsResponse(true, "Joint controlled");

    } else if (strcmp(type, "multi_joint_control") == 0) {
        // 多关节控制
        if (!doc["data"]["joints"].is<JsonArrayConst>()) {
            sendWsResponse(false, "Missing required fields in multi_joint_control");
            return;
        }

        JsonArrayConst joints = doc["data"]["joints"].as<JsonArrayConst>();
        int duration = doc["data"]["duration_ms"].as<int>();

        DEBUG_PRINTF("[CMD] Multi-joint control: %d joints\\n", (int)joints.size());
        sendWsResponse(true, "Multi-joint control received");
        // TODO: 完整实现多关节控制

    } else if (strcmp(type, "get_states") == 0) {
        // 获取状态
        broadcastJointStates();

    } else if (strcmp(type, "homing") == 0) {
        // 归零
        if (!HOMING_isBusy()) {
            HOMING_start();
            saveExtendsToNVS();
            for (int i = 0; i < JOINT_COUNT; i++) {
                g_jointAngles[i] = 0;
            }
            DEBUG_PRINTLN("[CMD] Homing executed");
            sendWsResponse(true, "Homing executed");
        } else {
            sendWsResponse(false, "Homing in progress");
        }

    } else {
        DEBUG_PRINTF("[CMD] Unknown command type: %s\\n", type);
        sendWsResponse(false, "Unknown command type");
    }
}

void sendWsResponse(bool success, const char* message) {
    DynamicJsonDocument response(256);
    response["type"] = "response";
    response["success"] = success;
    response["timestamp"] = millis();

    if (success) {
        response["data"]["executed"] = true;
    } else {
        response["error"]["code"] = "COMMAND_ERROR";
        response["error"]["message"] = message;
    }

    String output;
    serializeJson(response, output);
    wsServer.broadcastText(output);
}

void broadcastJointStates() {
    DynamicJsonDocument response(1024);
    response["type"] = "states_response";
    response["success"] = true;
    response["timestamp"] = millis();

    JsonArray jointsData = response["data"].to<JsonArray>();
    for (int i = 0; i < JOINT_COUNT; i++) {
        JsonObject joint = jointsData.createNestedObject();
        joint["joint_id"] = JOINT_NAMES[i];
        joint["angle"] = g_jointAngles[i];
        joint["load"] = 0.0;
    }

    String output;
    serializeJson(response, output);
    wsServer.broadcastText(output);
}
'''

def transform_firmware(content, version_config, version_name):
    """转换固件为WebSocket版本"""

    # 添加版本注释
    header = f'''// TetherIA - Open Source Hand
// Aero Hand Firmware Source Code - WebSocket版本 ({version_name})
//
// 改造说明:
// - 原始串口通信(Serial)已注释，替换为WiFi + WebSocket
// - 保留所有核心功能: HLSCL舵机控制、TaskSyncRead_Core1、软限位
// - 新增WiFi AP/STA模式、WebSocket服务器、JSON命令解析
//
// WebSocket协议: JSON格式，参考 docs/CONTROL_PROTOCOL.md

'''

    # 1. 添加WiFi/WebSocket头文件 (在Homing.h之后)
    if '#include "Homing.h"' in content:
        content = content.replace(
            '#include "Homing.h"',
            '#include "Homing.h"\n\n' + WS_HEADER.strip()
        )

    # 2. 在全局变量区域后添加WiFi/WebSocket全局变量
    # 找到 Preferences prefs; 之后添加
    if 'Preferences prefs;' in content:
        content = content.replace(
            'Preferences prefs;',
            'Preferences prefs;\n\n#include "config.h"\n\n' + WS_GLOBALS.strip()
        )

    # 2.5 添加前置声明 (sendAckFrame和sendU16Frame在原代码中定义较晚)
    fwd_decls = '''// 前置声明 (这些函数在原代码中定义较晚，但被更早的函数调用)
static inline void sendAckFrame(uint8_t header, const uint8_t* payload, size_t n);
static inline void sendU16Frame(uint8_t header, const uint16_t data[7]);
'''
    # v0.1.0没有热保护，需要添加stub定义
    if version_config['temp_cutoff'] is None:
        stub_defs = '''// 热保护stub (v0.1.0没有热保护)
#define HOT_TORQUE_LIMIT 1023
static inline bool isHot(uint8_t ch) { (void)ch; return false; }
static inline uint16_t u16_min(uint16_t a, uint16_t b) { return (a < b) ? a : b; }
'''
    else:
        stub_defs = '''// 前置声明 (热保护函数)
static inline bool isHot(uint8_t ch);
static inline uint16_t u16_min(uint16_t a, uint16_t b);
'''
    if 'Preferences prefs;' in content:
        content = content.replace(
            'Preferences prefs;\n\n#include "config.h"',
            'Preferences prefs;\n\n#include "config.h"\n\n' + fwd_decls.strip() + '\n\n' + stub_defs.strip()
        )

    # 3. 在setup()之前添加函数声明
    # 找到第一个函数声明位置
    if 'bool HOMING_isBusy();' in content:
        content = content.replace(
            'bool HOMING_isBusy();',
            WS_FUNCTIONS_DECL.strip() + '\n\nbool HOMING_isBusy();'
        )

    # 4. 在setup()中的舵机初始化之后添加WiFi/WebSocket初始化
    # 找到 "xTaskCreatePinnedToCore" 在setup()中的位置
    if 'xTaskCreatePinnedToCore(TaskSyncRead_Core1' in content:
        # 在它之前添加WiFi/WS初始化
        content = content.replace(
            '    xTaskCreatePinnedToCore(TaskSyncRead_Core1',
            '\n    // ========================================\n    // WiFi / WebSocket 初始化 (新增)\n    // ========================================\n    setupWiFi();\n\n    // 初始化WebSocket服务\n    wsServer.begin(WS_PORT);\n\n    DEBUG_PRINTLN("[SETUP] Setup complete!");\n    digitalWrite(STATUS_LED, HIGH);\n\n    // ========================================\n    // 原有初始化代码结束\n    // ========================================\n\n    xTaskCreatePinnedToCore(TaskSyncRead_Core1'
        )

    # 5. 修改setup()中的串口初始化为调试模式
    content = content.replace(
        'Serial.begin(921600);',
        '// Serial.begin(921600);  // 已注释，原用于USB调试'
    )

    # 6. 在loop()之前添加WiFi/WS函数定义
    if 'void loop()' in content:
        content = content.replace(
            'void loop()',
            WS_WIFI_SETUP.strip() + '\n\n' +
            WS_EVENT_HANDLER.strip() + '\n\n' +
            WS_CMD_FUNCS.strip() + '\n\nvoid loop()'
        )

    # 7. 修改loop() - 注释掉串口处理，添加WebSocket处理
    old_loop = '''void loop() {
  static uint32_t last_cmd_ms = 0;

  // Gate all host input during homing
  if (HOMING_isBusy()) {
    while (Serial.available()) { Serial.read(); }
    vTaskDelay(pdMS_TO_TICKS(5));
    return;
  }

  // ------ Soft Limit Check -------
  checkAndEnforceSoftLimits();

  // Process exactly one complete 16-byte frame when available
  if (Serial.available() >= 16) {
    int op = Serial.read();
    if (op >= 0) {
      if (handleHostFrame((uint8_t)op)) {
        last_cmd_ms = millis();
        return;
      }
    }
  }
}'''

    new_loop = '''void loop() {
  // 处理WebSocket事件
  wsServer.loop();

  // ------ Soft Limit Check (保留原有功能) -------
  checkAndEnforceSoftLimits();

  delay(COMMAND_INTERVAL_MS);
}'''

    if old_loop in content:
        content = content.replace(old_loop, new_loop)
    else:
        # 尝试更宽松的匹配
        content = re.sub(
            r'void loop\(\) \{.*?\n\}',
            new_loop,
            content,
            flags=re.DOTALL
        )

    # 8. 完全删除handleHostFrame函数 (WebSocket替代了它)
    # 找到函数开始和结束，删除整个函数体
    content = re.sub(
        r'static bool handleHostFrame\(uint8_t op\).*?^\}',
        r'// handleHostFrame已删除 - 由WebSocket命令处理替代\nstatic bool handleHostFrame(uint8_t op) { return true; }',
        content,
        flags=re.MULTILINE | re.DOTALL
    )

    return header + content


def main():
    base_path = os.path.dirname(os.path.abspath(__file__))
    firmware_src_path = os.path.join(os.path.dirname(base_path), 'firmware_src')
    firmware_ws_path = base_path

    for version, config in VERSIONS.items():
        src_file = os.path.join(firmware_src_path, version, 'firmware.ino')
        dst_file = os.path.join(firmware_ws_path, version, 'firmware.ino')

        if not os.path.exists(src_file):
            print(f"Warning: {src_file} not found, skipping")
            continue

        with open(src_file, 'r', encoding='utf-8') as f:
            content = f.read()

        transformed = transform_firmware(content, config, version)

        with open(dst_file, 'w', encoding='utf-8') as f:
            f.write(transformed)

        print(f"Generated: {dst_file}")


if __name__ == '__main__':
    main()
