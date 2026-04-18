/**
 * Aero Hand WiFi - ESP32 WebSocket固件
 *
 * 功能:
 * - WiFi AP模式开启热点 (或STA模式连接路由器)
 * - WebSocket服务端接收上位机指令
 * - 解析JSON指令并控制舵机
 *
 * 作者: Aero Hand Open Team
 * 版本: 1.0
 * 日期: 2026-03-28
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WebSocketsServer.h>
#include <ArduinoJson.h>
#include <string.h>

#include "config.h"
#include "webSocketServer.h"
#include "servoControl.h"

// 全局对象
AeroWebSocketServer wsServer;
ServoControl servoControl;

// 关节名称定义
const char* const JOINT_NAMES[JOINT_COUNT] = {
    "thumb_proximal",
    "thumb_distal",
    "index_proximal",
    "index_middle",
    "index_distal",
    "middle_proximal",
    "middle_middle",
    "middle_distal",
    "ring_proximal",
    "ring_middle",
    "ring_distal",
    "pinky_proximal",
    "pinky_middle",
    "pinky_distal",
    "thumb_rotation"
};

// 当前关节角度
float g_jointAngles[JOINT_COUNT] = {0};

// LED引脚 (板载LED，通常是GPIO 48 或 2)
#ifdef LED_BUILTIN
#define STATUS_LED LED_BUILTIN
#else
#define STATUS_LED 48
#endif

// ============================================
// 函数声明
// ============================================

uint8_t getJointNumber(const char* jointId);
void handleCommand(uint8_t clientNum, const char* payload, size_t length);
void processJsonCommand(uint8_t clientNum, const JsonDocument& doc);
void sendResponse(uint8_t clientNum, bool success, const char* message);
void setupWiFi();
void blinkLED(int times);

// ============================================
// 初始化
// ============================================

void setup() {
    // 初始化调试串口
    DEBUG_BEGIN();
    DEBUG_PRINTLN();
    DEBUG_PRINTLN("=================================");
    DEBUG_PRINTLN("Aero Hand WiFi Firmware v1.0");
    DEBUG_PRINTLN("=================================");

    // 初始化LED
    pinMode(STATUS_LED, OUTPUT);
    digitalWrite(STATUS_LED, LOW);  // LED亮表示启动中

    // 初始化舵机控制
    servoControl.begin(SERVO_TX_PIN, SERVO_RX_PIN, SERVO_BAUDRATE);
    DEBUG_PRINTLN("[SETUP] Servo control initialized");

    // 连接WiFi
    setupWiFi();

    // 初始化WebSocket服务
    wsServer.begin(WS_PORT);
    wsServer.onMessage(handleCommand);
    wsServer.onConnect([](uint8_t num) {
        DEBUG_PRINTF("[WS] Client %u connected\n", num);
        blinkLED(2);
    });
    wsServer.onDisconnect([](uint8_t num) {
        DEBUG_PRINTF("[WS] Client %u disconnected\n", num);
    });

    DEBUG_PRINTLN("[SETUP] Setup complete!");
    digitalWrite(STATUS_LED, HIGH);  // LED灭表示启动完成
}

// ============================================
// 主循环
// ============================================

void loop() {
    // 处理WebSocket事件
    wsServer.loop();

    // 其他周期性任务可以添加在这里
    delay(COMMAND_INTERVAL_MS);
}

// ============================================
// WiFi设置
// ============================================

void setupWiFi() {
#if WIFI_MODE == 1
    // AP模式 - ESP32开热点
    DEBUG_PRINTF("[WIFI] Starting AP mode: %s\n", AP_SSID);

    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASSWORD, AP_CHANNEL);

    IPAddress IP = WiFi.softAPIP();
    DEBUG_PRINT("[WIFI] AP IP address: ");
    DEBUG_PRINTLN(IP.toString());

#elif WIFI_MODE == 2
    // STA模式 - 连接路由器
    DEBUG_PRINTF("[WIFI] Connecting to: %s\n", STA_SSID);

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

// ============================================
// WebSocket消息处理
// ============================================

void handleCommand(uint8_t clientNum, const char* payload, size_t length) {
    // 解析JSON
    JsonDocument doc;

    DeserializationError error = deserializeJson(doc, payload, length);
    if (error) {
        DEBUG_PRINTF("[CMD] JSON parse error: %s\n", error.c_str());
        sendResponse(clientNum, false, error.c_str());
        return;
    }

    processJsonCommand(clientNum, doc);
}

void processJsonCommand(uint8_t clientNum, const JsonDocument& doc) {
    // 检查type字段是否存在 - ArduinoJson 7.x 用 is<T>() 检查类型
    // JsonDocumentoperator[]返回JsonVariantConst，用is<T>()检查类型
    if (!doc["type"].is<const char*>()) {
        sendResponse(clientNum, false,"Missing type field");
        return;
    }

    const char* type = doc["type"].as<const char*>();

    if (strcmp(type, "joint_control") == 0) {
        // 单关节控制 - 检查必要字段存在且类型正确
        if (!doc["data"]["joint_id"].is<const char*>() || !doc["data"]["angle"].is<float>()) {
            sendResponse(clientNum, false,"Missing required fields in joint_control");
            return;
        }

        const char* jointId = doc["data"]["joint_id"].as<const char*>();
        float angle = doc["data"]["angle"].as<float>();
        int duration = doc["data"]["duration_ms"].as<int>();

        uint8_t jointNum = getJointNumber(jointId);
        if (jointNum < JOINT_COUNT) {
            float minAngle = (jointNum == JOINT_THUMB_ROTATION) ? -30.0f : (SERVO_MIN_ANGLE / 10.0f);
            float maxAngle = (jointNum == JOINT_THUMB_ROTATION) ? 30.0f : (SERVO_MAX_ANGLE / 10.0f);
            float clampedAngle = constrain(angle, minAngle, maxAngle);
            int16_t angleInt = (int16_t)(clampedAngle * 10.0f);  // 转换为整数
            bool executed = servoControl.setAngle(jointNum, angleInt, duration > 0 ? duration : 500);
            if (executed) {
                g_jointAngles[jointNum] = clampedAngle;
                DEBUG_PRINTF("[CMD] Joint %s -> %.1f°\n", jointId, clampedAngle);
                sendResponse(clientNum, true,"Joint controlled");
            } else {
                sendResponse(clientNum, false,"Joint control failed");
            }
        } else {
            sendResponse(clientNum, false,"Invalid joint_id");
        }

    } else if (strcmp(type, "multi_joint_control") == 0) {
        // 多关节控制 - const JsonDocument 只能读取 JsonArrayConst
        if (!doc["data"]["joints"].is<JsonArrayConst>()) {
            sendResponse(clientNum, false,"Missing required fields in multi_joint_control");
            return;
        }

        JsonArrayConst joints = doc["data"]["joints"].as<JsonArrayConst>();
        int duration = doc["data"]["duration_ms"].as<int>();

        int count = 0;
        JointAngle angleList[JOINT_COUNT];
        float appliedAngles[JOINT_COUNT] = {0.0f};

        for (JsonObjectConst joint : joints) {
            // 检查字段存在且类型正确
            if (!joint["joint_id"].is<const char*>() || !joint["angle"].is<float>()) {
                continue;  // 跳过不完整的条目
            }

            if (count >= JOINT_COUNT) {
                break;
            }

            const char* jId = joint["joint_id"].as<const char*>();
            float angle = joint["angle"].as<float>();

            uint8_t jointNum = getJointNumber(jId);
            if (jointNum < JOINT_COUNT) {
                float minAngle = (jointNum == JOINT_THUMB_ROTATION) ? -30.0f : (SERVO_MIN_ANGLE / 10.0f);
                float maxAngle = (jointNum == JOINT_THUMB_ROTATION) ? 30.0f : (SERVO_MAX_ANGLE / 10.0f);
                float clampedAngle = constrain(angle, minAngle, maxAngle);
                angleList[count].joint_id = jointNum;
                angleList[count].angle = (int16_t)(clampedAngle * 10.0f);
                appliedAngles[jointNum] = clampedAngle;
                count++;
            }
        }

        if (count > 0) {
            bool executed = servoControl.setAngles(angleList, count, duration > 0 ? duration : 500);
            if (executed) {
                for (int i = 0; i < count; i++) {
                    g_jointAngles[angleList[i].joint_id] = appliedAngles[angleList[i].joint_id];
                }
                DEBUG_PRINTF("[CMD] Multi-joint: %d joints controlled\n", count);
                sendResponse(clientNum, true,"Multi-joint controlled");
            } else {
                sendResponse(clientNum, false,"Multi-joint control failed");
            }
        } else {
            sendResponse(clientNum, false,"No valid joints");
        }

    } else if (strcmp(type, "get_states") == 0) {
        // 获取状态
        JsonDocument response;
        response["type"] = "states_response";
        response["success"] = true;
        response["timestamp"] = millis();

        JsonArray jointsData = response["data"].to<JsonArray>();
        for (int i = 0; i < JOINT_COUNT; i++) {
            JsonObject joint = jointsData.add<JsonObject>();
            joint["joint_id"] = JOINT_NAMES[i];
            joint["angle"] = g_jointAngles[i];
            joint["load"] = 0.0;  // 简化版，实际可读取真实负载
        }

        String output;
        serializeJson(response, output);
        wsServer.sendText(clientNum, output);

    } else if (strcmp(type, "homing") == 0) {
        // 归零
        if (servoControl.isConnected()) {
            servoControl.homing();
            for (int i = 0; i < JOINT_COUNT; i++) {
                g_jointAngles[i] = 0;
            }
            DEBUG_PRINTLN("[CMD] Homing executed");
            sendResponse(clientNum, true,"Homing executed");
        } else {
            sendResponse(clientNum, false,"Homing unavailable");
        }

    } else {
        DEBUG_PRINTF("[CMD] Unknown command type: %s\n", type);
        sendResponse(clientNum, false,"Unknown command type");
    }
}

void sendResponse(uint8_t clientNum, bool success, const char* message) {
    JsonDocument response;
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
    wsServer.sendText(clientNum, output);
}

// ============================================
// 辅助函数
// ============================================

/**
 * 根据关节名称获取关节编号
 */
uint8_t getJointNumber(const char* jointId) {
    for (uint8_t i = 0; i < JOINT_COUNT; i++) {
        if (strcmp(jointId, JOINT_NAMES[i]) == 0) {
            return i;
        }
    }
    return 255;  // 无效ID
}

/**
 * LED闪烁
 */
void blinkLED(int times) {
    for (int i = 0; i < times; i++) {
        digitalWrite(STATUS_LED, LOW);
        delay(100);
        digitalWrite(STATUS_LED, HIGH);
        delay(100);
    }
}
