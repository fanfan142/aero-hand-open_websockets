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
#include <Preferences.h>
#include <string.h>

#include "config.h"
#include "webSocketServer.h"
#include "servoControl.h"

// 全局对象
AeroWebSocketServer wsServer;
ServoControl servoControl;
Preferences prefs;
String g_staSsid = STA_SSID;
String g_staPassword = STA_PASSWORD;
String g_staStaticIp = STA_STATIC_IP;
String g_staGateway = STA_GATEWAY;
String g_staSubnet = STA_SUBNET;
String g_staDns1 = STA_DNS1;
String g_staDns2 = STA_DNS2;
uint8_t g_wifiModeSetting = WIFI_MODE;

enum PendingWifiAction {
    WIFI_ACTION_NONE = 0,
    WIFI_ACTION_CONNECT_STA = 1,
    WIFI_ACTION_START_AP = 2,
    WIFI_ACTION_CLEAR_STA = 3,
};
static volatile uint8_t g_pendingWifiAction = WIFI_ACTION_NONE;
static uint32_t g_pendingWifiActionAt = 0;

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
float g_actuatorAngles[SERVO_COUNT] = {0};

static constexpr float ACTUATION_LOWER_LIMITS[SERVO_COUNT] = {-30.0f, 0.0f, -15.2789f, 0.0f, 0.0f, 0.0f, 0.0f};
static constexpr float ACTUATION_UPPER_LIMITS[SERVO_COUNT] = {30.0f, 104.1250f, 247.1500f, 288.1603f, 288.1603f, 288.1603f, 288.1603f};

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
void sendWifiStatus(uint8_t clientNum);
void loadWiFiConfig();
void saveWiFiConfig();
void clearWiFiConfig();
void startApMode();
bool connectToSta(bool fallbackToAp);
const char* getWifiModeName();
String getCurrentIp();
void setupWiFi();
void blinkLED(int times);
void scheduleWifiAction(PendingWifiAction action);
void processPendingWifiAction();

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
    loadWiFiConfig();
    setupWiFi();

    // 初始化WebSocket服务
    wsServer.begin(WS_PORT);
    wsServer.onMessage(handleCommand);
    wsServer.onConnect([](uint8_t num) {
        DEBUG_PRINTF("[WS] Client %u connected\n", num);
        blinkLED(2);
        JsonDocument doc;
        doc["type"] = "hand_info";
        doc["hand_type"] = (HAND_TYPE == 0) ? "Left" : "Right";
        doc["wifi_mode"] = getWifiModeName();
        doc["configured_wifi_mode"] = (g_wifiModeSetting == AH_WIFI_MODE_AP) ? "AP" : (g_wifiModeSetting == AH_WIFI_MODE_STA ? "STA" : "DUAL");
        doc["ip"] = getCurrentIp();
        doc["firmware_type"] = "esp32_wifi";
        doc["firmware_version"] = "v1.0";
        String output;
        serializeJson(doc, output);
        wsServer.sendText(num, output);
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
    processPendingWifiAction();

    // 其他周期性任务可以添加在这里
    delay(COMMAND_INTERVAL_MS);
}

// ============================================
// WiFi设置
// ============================================

void loadWiFiConfig() {
    prefs.begin("wifi", true);
    g_wifiModeSetting = prefs.getUChar("mode", WIFI_MODE);
    g_staSsid = prefs.getString("sta_ssid", STA_SSID);
    g_staPassword = prefs.getString("sta_pass", STA_PASSWORD);
    g_staStaticIp = prefs.getString("sta_ip", STA_STATIC_IP);
    g_staGateway = prefs.getString("sta_gw", STA_GATEWAY);
    g_staSubnet = prefs.getString("sta_mask", STA_SUBNET);
    g_staDns1 = prefs.getString("sta_dns1", STA_DNS1);
    g_staDns2 = prefs.getString("sta_dns2", STA_DNS2);
    prefs.end();
}

void saveWiFiConfig() {
    prefs.begin("wifi", false);
    prefs.putUChar("mode", g_wifiModeSetting);
    prefs.putString("sta_ssid", g_staSsid);
    prefs.putString("sta_pass", g_staPassword);
    prefs.putString("sta_ip", g_staStaticIp);
    prefs.putString("sta_gw", g_staGateway);
    prefs.putString("sta_mask", g_staSubnet);
    prefs.putString("sta_dns1", g_staDns1);
    prefs.putString("sta_dns2", g_staDns2);
    prefs.end();
}

void clearWiFiConfig() {
    g_staSsid = "";
    g_staPassword = "";
    g_staStaticIp = STA_STATIC_IP;
    g_staGateway = STA_GATEWAY;
    g_staSubnet = STA_SUBNET;
    g_staDns1 = STA_DNS1;
    g_staDns2 = STA_DNS2;
    g_wifiModeSetting = AH_WIFI_MODE_AP;
    prefs.begin("wifi", false);
    prefs.remove("sta_ssid");
    prefs.remove("sta_pass");
    prefs.remove("sta_ip");
    prefs.remove("sta_gw");
    prefs.remove("sta_mask");
    prefs.remove("sta_dns1");
    prefs.remove("sta_dns2");
    prefs.putUChar("mode", g_wifiModeSetting);
    prefs.end();
}

static bool parseIp(const String& value, IPAddress& out) {
    return !value.isEmpty() && out.fromString(value);
}

static void applyStaStaticConfig() {
    IPAddress ip;
    IPAddress gateway;
    IPAddress subnet;
    IPAddress dns1;
    IPAddress dns2;
    if (!parseIp(g_staStaticIp, ip)) {
        return;
    }
    parseIp(g_staGateway, gateway);
    parseIp(g_staSubnet, subnet);
    parseIp(g_staDns1, dns1);
    parseIp(g_staDns2, dns2);
    if (gateway == IPAddress(0, 0, 0, 0)) gateway = IPAddress(192, 168, 1, 1);
    if (subnet == IPAddress(0, 0, 0, 0)) subnet = IPAddress(255, 255, 255, 0);
    if (dns1 == IPAddress(0, 0, 0, 0)) dns1 = gateway;
    if (dns2 == IPAddress(0, 0, 0, 0)) dns2 = dns1;
    WiFi.config(ip, gateway, subnet, dns1, dns2);
}

void startApMode() {
    WiFi.disconnect(true, true);
    delay(100);
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASSWORD, AP_CHANNEL);
    DEBUG_PRINT("[WIFI] AP IP address: ");
    DEBUG_PRINTLN(WiFi.softAPIP().toString());
}

bool connectToSta(bool fallbackToAp) {
    if (g_staSsid.isEmpty()) {
        DEBUG_PRINTLN("[WIFI] STA SSID is empty");
        if (fallbackToAp) {
            startApMode();
        }
        return false;
    }

    WiFi.disconnect(true, true);
    delay(100);
    WiFi.mode(WIFI_STA);
    applyStaStaticConfig();
    WiFi.begin(g_staSsid.c_str(), g_staPassword.c_str());
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
        return true;
    }

    DEBUG_PRINTLN();
    DEBUG_PRINTLN("[WIFI] STA connection failed");
    if (fallbackToAp) {
        DEBUG_PRINTLN("[WIFI] Falling back to AP");
        startApMode();
    }
    return false;
}

const char* getWifiModeName() {
    wifi_mode_t mode = WiFi.getMode();
    if (mode == WIFI_AP) return "AP";
    if (mode == WIFI_STA) return "STA";
    if (mode == WIFI_AP_STA) return "AP_STA";
    return "UNKNOWN";
}

String getCurrentIp() {
    wifi_mode_t mode = WiFi.getMode();
    if (mode == WIFI_AP || mode == WIFI_AP_STA) {
        return WiFi.softAPIP().toString();
    }
    if (mode == WIFI_STA) {
        return WiFi.localIP().toString();
    }
    return "0.0.0.0";
}

void setupWiFi() {
    if (g_wifiModeSetting == AH_WIFI_MODE_STA) {
        DEBUG_PRINTF("[WIFI] Connecting to STA: %s\n", g_staSsid.c_str());
        connectToSta(true);
    } else if (g_wifiModeSetting == AH_WIFI_MODE_DUAL) {
        DEBUG_PRINTF("[WIFI] Starting dual mode, trying STA: %s\n", g_staSsid.c_str());
        connectToSta(true);
    } else {
        DEBUG_PRINTF("[WIFI] Starting AP mode: %s\n", AP_SSID);
        startApMode();
    }
}

void scheduleWifiAction(PendingWifiAction action) {
    g_pendingWifiAction = action;
    g_pendingWifiActionAt = millis() + 500;
}

void processPendingWifiAction() {
    if (g_pendingWifiAction == WIFI_ACTION_NONE) return;
    uint32_t now = millis();
    if ((int32_t)(now - g_pendingWifiActionAt) < 0) return;

    PendingWifiAction action = (PendingWifiAction)g_pendingWifiAction;
    g_pendingWifiAction = WIFI_ACTION_NONE;

    if (action == WIFI_ACTION_CONNECT_STA) {
        bool connected = connectToSta(true);
        if (connected) {
            g_wifiModeSetting = AH_WIFI_MODE_DUAL;
            saveWiFiConfig();
        }
    } else if (action == WIFI_ACTION_START_AP) {
        g_wifiModeSetting = AH_WIFI_MODE_AP;
        saveWiFiConfig();
        startApMode();
    } else if (action == WIFI_ACTION_CLEAR_STA) {
        clearWiFiConfig();
        startApMode();
    }
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

    } else if (strcmp(type, "actuator_control") == 0) {
        if (!doc["data"]["actuators"].is<JsonArrayConst>()) {
            sendResponse(clientNum, false, "Missing actuators in actuator_control");
            return;
        }

        JsonArrayConst actuators = doc["data"]["actuators"].as<JsonArrayConst>();
        int duration = doc["data"]["duration_ms"].as<int>();
        int validCount = 0;

        for (JsonObjectConst actuator : actuators) {
            if (!actuator["id"].is<int>() || !actuator["angle"].is<float>()) {
                continue;
            }
            int id = actuator["id"].as<int>();
            if (id < 0 || id >= SERVO_COUNT) {
                continue;
            }
            float angle = actuator["angle"].as<float>();
            g_actuatorAngles[id] = constrain(angle, ACTUATION_LOWER_LIMITS[id], ACTUATION_UPPER_LIMITS[id]);
            validCount++;
        }

        if (validCount <= 0) {
            sendResponse(clientNum, false, "No valid actuators");
            return;
        }

        bool executed = servoControl.setActuators(g_actuatorAngles, duration > 0 ? duration : 500);
        if (executed) {
            for (int i = 0; i < JOINT_COUNT; i++) {
                g_jointAngles[i] = 0;
            }
            DEBUG_PRINTF("[CMD] Actuator control: %d actuators\n", validCount);
            sendResponse(clientNum, true, "Actuator control executed");
        } else {
            sendResponse(clientNum, false, "Actuator control failed");
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
            for (int i = 0; i < SERVO_COUNT; i++) {
                g_actuatorAngles[i] = 0;
            }
            DEBUG_PRINTLN("[CMD] Homing executed");
            sendResponse(clientNum, true,"Homing executed");
        } else {
            sendResponse(clientNum, false,"Homing unavailable");
        }

    } else if (strcmp(type, "wifi_status") == 0) {
        sendWifiStatus(clientNum);

    } else if (strcmp(type, "wifi_config_set") == 0) {
        if (!doc["data"]["sta_ssid"].is<const char*>() || !doc["data"]["sta_password"].is<const char*>()) {
            sendResponse(clientNum, false, "Missing required fields in wifi_config_set");
            return;
        }
        g_staSsid = doc["data"]["sta_ssid"].as<const char*>();
        g_staPassword = doc["data"]["sta_password"].as<const char*>();
        if (doc["data"]["sta_static_ip"].is<const char*>()) g_staStaticIp = doc["data"]["sta_static_ip"].as<const char*>();
        if (doc["data"]["sta_gateway"].is<const char*>()) g_staGateway = doc["data"]["sta_gateway"].as<const char*>();
        if (doc["data"]["sta_subnet"].is<const char*>()) g_staSubnet = doc["data"]["sta_subnet"].as<const char*>();
        if (doc["data"]["sta_dns1"].is<const char*>()) g_staDns1 = doc["data"]["sta_dns1"].as<const char*>();
        if (doc["data"]["sta_dns2"].is<const char*>()) g_staDns2 = doc["data"]["sta_dns2"].as<const char*>();
        g_wifiModeSetting = AH_WIFI_MODE_DUAL;
        saveWiFiConfig();
        DEBUG_PRINTF("[WIFI] Saved STA config for SSID: %s\n", g_staSsid.c_str());
        sendResponse(clientNum, true, "WiFi config saved");
        sendWifiStatus(clientNum);

    } else if (strcmp(type, "wifi_connect_sta") == 0) {
        sendResponse(clientNum, true, "STA switch scheduled");
        sendWifiStatus(clientNum);
        scheduleWifiAction(WIFI_ACTION_CONNECT_STA);

    } else if (strcmp(type, "wifi_start_ap") == 0) {
        sendResponse(clientNum, true, "AP switch scheduled");
        sendWifiStatus(clientNum);
        scheduleWifiAction(WIFI_ACTION_START_AP);

    } else if (strcmp(type, "wifi_clear_sta") == 0) {
        sendResponse(clientNum, true, "STA config clear scheduled");
        sendWifiStatus(clientNum);
        scheduleWifiAction(WIFI_ACTION_CLEAR_STA);

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

void sendWifiStatus(uint8_t clientNum) {
    JsonDocument response;
    response["type"] = "wifi_status";
    response["timestamp"] = millis();
    response["data"]["mode"] = getWifiModeName();
    response["data"]["ip"] = getCurrentIp();
    if (!g_staSsid.isEmpty()) {
        response["data"]["sta_ssid"] = g_staSsid;
    }
    response["data"]["sta_static_ip"] = g_staStaticIp;
    response["data"]["sta_gateway"] = g_staGateway;
    response["data"]["sta_subnet"] = g_staSubnet;
    response["data"]["sta_dns1"] = g_staDns1;
    response["data"]["sta_dns2"] = g_staDns2;
    response["data"]["configured_mode"] = (g_wifiModeSetting == AH_WIFI_MODE_AP) ? "AP" : (g_wifiModeSetting == AH_WIFI_MODE_STA ? "STA" : "DUAL");

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
