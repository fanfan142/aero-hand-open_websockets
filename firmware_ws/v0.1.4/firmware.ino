// TetherIA - Open Source Hand
// Aero Hand Firmware Source Code - WebSocket版本 (v0.1.4)
//
// 改造说明:
// - 原始串口通信(Serial)已注释，替换为WiFi + WebSocket
// - 保留所有核心功能: HLSCL舵机控制、TaskSyncRead_Core1、软限位
// - 新增WiFi AP/STA模式、WebSocket服务器、JSON命令解析
//
// WebSocket协议: JSON格式，参考 docs/CONTROL_PROTOCOL.md

// TetherIA - Open Source Hand
// Aero Hand Firmware Source Code
#include <Arduino.h>
#include <Wire.h>
#include <HLSCL.h>
#include <Preferences.h>
#include "HandConfig.h"
#include "Homing.h"

// ============================================
// WiFi / WebSocket 相关头文件 (新增)
// ============================================
#include <WiFi.h>
#include <WebSocketsServer.h>
#include <ArduinoJson.h>

#include "webSocketServer.h"
#include "esp_system.h"

HLSCL hlscl;
Preferences prefs;

#include "config.h"

// 前置声明 (这些函数在原代码中定义较晚，但被更早的函数调用)
static inline void sendAckFrame(uint8_t header, const uint8_t* payload, size_t n);
static inline void sendU16Frame(uint8_t header, const uint16_t data[7]);

// 前置声明 (热保护函数)
static inline bool isHot(uint8_t ch);
static inline uint16_t u16_min(uint16_t a, uint16_t b);

// ============================================
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

// ---- UART pins to the servo bus (ESP32-S3 XIAO: RX=2, TX=3) ----
#define SERIAL2_TX_PIN 3
#define SERIAL2_RX_PIN 2

// ---- Servo IDs (declare at top) ----
const uint8_t SERVO_IDS[7] = { 0, 1, 2, 3, 4, 5, 6 };

ServoData sd[7];

// ---- Constants for Control Code byte ----
static const uint8_t HOMING    = 0x01;
static const uint8_t SET_ID    = 0x02;
static const uint8_t TRIM      = 0x03;
static const uint8_t CTRL_POS  = 0x11;
static const uint8_t CTRL_TOR  = 0x12;
static const uint8_t GET_POS   = 0x22;
static const uint8_t GET_VEL   = 0x23;
static const uint8_t GET_CURR  = 0x24;
static const uint8_t GET_TEMP  = 0x25;
static const uint8_t SET_SPE   = 0x31;
static const uint8_t SET_TOR   = 0x32;

// ---- Defaults for SyncWritePosEx ----
static uint16_t g_speed[7]  = {32766,32766,32766,32766,32766,32766,32766};
static uint8_t  g_accel[7]  = {0,0,0,0,0,0,0};     // 0..255
static uint16_t g_torque[7] = {700,700,700,700,700,700,700}; // 0....1000
static const uint16_t HOLD_MAG = 5;       // the minimal torque actually commanded to the motors during the torque mode. Any torque below that will not be used.

// Last commanded torque per servo (signed)
static int16_t g_lastTorqueCmd[7] = {0};

// ---- Thermal torque limiting (GLOBAL PARAMETERS) ----
static uint8_t  TEMP_CUTOFF_C    = 70;    // °C cutoff
static uint16_t HOT_TORQUE_LIMIT = 500;   // clamp torque when motor exceeds TEMP_CUTOFF_C 

// ----- Registers / constants (Mapped as per Feetech Servo HLS3606M) -----
#define REG_ID                 0x05       // ID register
#define REG_CURRENT_LIMIT      28         // decimal address (word)
#define BROADCAST_ID           0xFE
#define SCAN_MIN               0
#define SCAN_MAX               253
#define REG_BLOCK_LEN          15
#define REG_BLOCK_START        56

// ----- Structure for the Metrics of Servo -------
struct ServoMetrics {
  uint16_t pos[7];
  uint16_t vel[7];
  uint16_t cur[7];
  uint16_t tmp[7];
};
static ServoMetrics gMetrics;

// -------- Global Control Mode State  ---------
enum ControlMode{
  MODE_POS=0,
  MODE_TORQUE=2
};
static ControlMode g_currentMode = MODE_POS;

// ----- Semaphores for Metrics and Bus for acquiring lock and release it -----
static SemaphoreHandle_t gMetricsMux;
SemaphoreHandle_t gBusMux = nullptr;

// ----- Homing module API Calls (provided below as .h/.cpp) --------
// ============================================
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

bool HOMING_isBusy();
void HOMING_start();

// ---- Helper function to read latest temp from servo
static inline uint8_t getTempC(uint8_t ch) {
  uint8_t t = 0;
  if (gMetricsMux) xSemaphoreTake(gMetricsMux, portMAX_DELAY);
  t = (uint8_t)gMetrics.tmp[ch];
  if (gMetricsMux) xSemaphoreGive(gMetricsMux);
  return t;
}

static inline bool isHot(uint8_t ch) {
  return getTempC(ch) >= TEMP_CUTOFF_C;
}

static inline uint16_t u16_min(uint16_t a, uint16_t b) { return (a < b) ? a : b; }

// ---- Set-ID helpers for setting ID ---
extern void runReIdScanAndSet(uint8_t Id, uint16_t currentLimit);
static volatile int g_lastFoundId; 

// ----- Helper Functions for Set-ID Mode -----
static bool scanRequireSingleServo(uint8_t* outId, uint8_t requestedNewId) {
  uint8_t first = 0xFF;
  int count = 0;
  if (gBusMux) xSemaphoreTake(gBusMux, portMAX_DELAY);
  for (int id = SCAN_MIN; id <= SCAN_MAX; ++id) {
    if (id == BROADCAST_ID) continue;        
    (void)hlscl.Ping((uint8_t)id);
    if (!hlscl.getLastError()) {
      if (count == 0) first = (uint8_t)id;
      ++count;
      if (count > 1) break;                    
    }
  }
  if (gBusMux) xSemaphoreGive(gBusMux);
  if (count == 1) {
    if (outId) *outId = first;
    return true;
  }
  if (count == 0) {
    uint8_t ack6[6] = { 0xFF, 0x00, requestedNewId, 0x00, 0x00, 0x00 };
    sendAckFrame(SET_ID, ack6, sizeof(ack6));
    return false;
  }
  uint8_t ack14[14] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
  sendAckFrame(SET_ID, ack14, sizeof(ack14));
  return false;
}
static void sendSetIdAck(uint8_t oldId, uint8_t newId, uint16_t curLimitWord) {
  uint16_t vals[7] = {0};
  vals[0] = oldId;
  vals[1] = newId;
  vals[2] = curLimitWord;
  uint8_t out[2 + 7*2];
  out[0] = SET_ID;  // 0x03
  out[1] = 0x00;    // filler
  for (int i = 0; i < 7; ++i) {
    out[2 + 2*i + 0] = (uint8_t)(vals[i] & 0xFF);
    out[2 + 2*i + 1] = (uint8_t)((vals[i] >> 8) & 0xFF);
  }
  Serial.write(out, sizeof(out));
}
// ---- Helper function to save and loadManual extends from NVS ------
static void loadManualExtendsFromNVS() {
  prefs.begin("hand", true);  // read-only
  for (uint8_t i = 0; i < 7; ++i) {
    // Store extends per logical channel index, not servo bus ID.
    String key = "ext" + String(i);
    int v = prefs.getInt(key.c_str(), -1);
    if (v >= 0 && v <= 4095) {
      sd[i].extend_count = v;
    }
  }
}

static void saveExtendsToNVS() {
  prefs.begin("hand", false);  // RW
  for (uint8_t i = 0; i < 7; ++i) {
    // Store extends per logical channel index, not servo bus ID.
    String kext = "ext" + String(i);
    prefs.putInt(kext.c_str(), (int)sd[i].extend_count);
  }
  prefs.end();
}

// -------- Soft-Limit Safety for Servo motors
static void checkAndEnforceSoftLimits()
{
  static bool torqueLimited[7] = {false};
  static uint32_t lastCheckMs = 0;
  uint32_t now = millis();
  if (now - lastCheckMs < 20) return;
  lastCheckMs = now;

  if (g_currentMode != MODE_TORQUE) {
    // Only apply soft limits during torque control.
    for (int i = 0; i < 7; ++i) torqueLimited[i] = false;
    return;
  }

  if (gBusMux) xSemaphoreTake(gBusMux, portMAX_DELAY);
  for (uint8_t i = 0; i < 7; ++i) {
    uint8_t id = SERVO_IDS[i];
    int pos = hlscl.ReadPos(id);
    if (pos < 0) pos += 32768;
    uint16_t raw = pos % 4096;
    uint16_t ext = sd[i].extend_count;
    uint16_t gra = sd[i].grasp_count;
    uint16_t rawMin = min(ext, gra);
    uint16_t rawMax = max(ext, gra);
    bool inRange = (raw >= rawMin && raw <= rawMax);
    if (!inRange && !torqueLimited[i]) {
      int16_t limitedTorque = (g_lastTorqueCmd[i] >= 0) ? 200 : -200;
      hlscl.WriteEle(id, limitedTorque);
      torqueLimited[i] = true;
    } else if (inRange && torqueLimited[i]) {
      hlscl.WriteEle(id, g_lastTorqueCmd[i]);
      torqueLimited[i] = false;
    }
  }
  if (gBusMux) xSemaphoreGive(gBusMux);
}


// ---- Helper function  for raw to U16 and U16 to raw ----
static inline uint16_t mapRawToU16(uint8_t ch, uint16_t raw) {
  int32_t ext  = sd[ch].extend_count;
  int32_t gra  = sd[ch].grasp_count;
  int32_t span = gra - ext;
  if (span == 0) return 0;  // avoid divide-by-zero
  int32_t val = ((int32_t)(raw - ext) * 65535L) / span;
  //Clamp
  if (val < 0) val = 0;
  if (val > 65535) val = 65535;
  return (uint16_t)val;
}
static inline uint16_t mapU16ToRaw(uint8_t ch, uint16_t u16) {
  int32_t ext = sd[ch].extend_count;
  int32_t gra = sd[ch].grasp_count;
  int32_t raw32;
  if (ext == 0 && gra == 0) {
    raw32 = ((uint64_t)u16 * 4095u) / 65535u;
  } else {
    raw32 = ext + ((int64_t)u16 * (gra - ext)) / 65535LL;
  }
  // clamp
  if (raw32 < 0)    raw32 = 0;
  if (raw32 > 4095) raw32 = 4095;
  return (uint16_t)raw32;
}

// ---- Helper Functions for u16, Decode to sign and copy values in u16 format----
static inline uint16_t leu_u16(const uint8_t *p) {
  return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}
static inline int16_t decode_signmag15(uint8_t lo, uint8_t hi) {
  uint16_t mag = ((uint16_t)(hi & 0x7F) << 8) | lo;  // 15-bit magnitude
  return (hi & 0x80) ? -(int16_t)mag : (int16_t)mag;
}
static inline void copy7_u16(uint16_t dst[7], const uint16_t src[7]) {
  for (int i = 0; i < 7; ++i) dst[i] = src[i];
}

// ----- Helper function to send u16 frame for POS,SPD,VEL,CURR and sendACK Packet ----
static inline void sendU16Frame(uint8_t header, const uint16_t data[7]) {
  uint8_t out[2 + 7*2];
  out[0] = header;
  out[1] = 0x00; // filler
  for (int i = 0; i < 7; ++i) {
    out[2 + 2*i + 0] = (uint8_t)(data[i] & 0xFF);
    out[2 + 2*i + 1] = (uint8_t)((data[i] >> 8) & 0xFF);
  }
  Serial.write(out, sizeof(out)); 
}
static inline void sendAckFrame(uint8_t header, const uint8_t* payload, size_t n) {
  uint8_t out[16];
  out[0] = header;
  out[1] = 0x00; // filler
  memset(out + 2, 0, 14);
  if (payload && n) {
    if (n > 14) n = 14;
    memcpy(out + 2, payload, n);
  }
  Serial.write(out, sizeof(out));
}

// ----- Functions to Send POS, VEL, CURR, TEMP -----
void sendPositions() {
  uint16_t buf[7];
  xSemaphoreTake(gMetricsMux, portMAX_DELAY);
  copy7_u16(buf, gMetrics.pos);
  xSemaphoreGive(gMetricsMux);
  sendU16Frame(GET_POS, buf);
}
void sendVelocities() {
  uint16_t buf[7];
  xSemaphoreTake(gMetricsMux, portMAX_DELAY);
  copy7_u16(buf, gMetrics.vel);
  xSemaphoreGive(gMetricsMux);
  sendU16Frame(GET_VEL, buf);
}
void sendCurrents() {
  uint16_t buf[7];
  xSemaphoreTake(gMetricsMux, portMAX_DELAY);
  copy7_u16(buf, gMetrics.cur);
  xSemaphoreGive(gMetricsMux);
  sendU16Frame(GET_CURR, buf);
}
void sendTemps() {
  uint16_t buf[7];
  xSemaphoreTake(gMetricsMux, portMAX_DELAY);
  copy7_u16(buf, gMetrics.tmp);
  xSemaphoreGive(gMetricsMux);
  sendU16Frame(GET_TEMP, buf);
}

// ----- Task - Sync Read running always on Core 1 ----- 
static void TaskSyncRead_Core1(void *arg) {
  uint8_t  rx[REG_BLOCK_LEN];          // 15 bytes
  uint16_t pos[7], vel[7], cur[7], tmp[7];
  const TickType_t period = pdMS_TO_TICKS(10);   // Change Frequency of Running here, 5 -200 Hz, 10-100 Hz, 20 -50 Hz
  TickType_t nextWake = xTaskGetTickCount();
  for (;;) {
    // try-lock: if control is using the bus, skip this cycle
    if (gBusMux && xSemaphoreTake(gBusMux, 0) != pdTRUE) {
      vTaskDelayUntil(&nextWake, period);
      continue;
    }
    bool ok = true;
    // one TX for the whole group (15-byte slice)
    hlscl.syncReadPacketTx((uint8_t*)SERVO_IDS, 7, REG_BLOCK_START, REG_BLOCK_LEN);
    for (uint8_t i = 0; i < 7; ++i) {
      if (!hlscl.syncReadPacketRx(SERVO_IDS[i], rx)) { ok = false; break; }
      uint16_t raw = leu_u16(&rx[0]);
      pos[i] = mapRawToU16(i,raw);                     // Position (unsigned)
      vel[i] = decode_signmag15(rx[2], rx[3]);                  // velocity (signed)
      tmp[i] = rx[7];                                           // temperature (unsigned, 1 byte)
      cur[i] = decode_signmag15(rx[13], rx[14]);                // current (signed)
      //vTaskDelay(1);
    }
    if (gBusMux) xSemaphoreGive(gBusMux);
    if (ok) {
      xSemaphoreTake(gMetricsMux, portMAX_DELAY);
      for (int i = 0; i < 7; ++i) {
        gMetrics.pos[i] = pos[i];
        gMetrics.vel[i] = vel[i];
        gMetrics.tmp[i] = tmp[i];
        gMetrics.cur[i] = cur[i];
      }
      xSemaphoreGive(gMetricsMux);
    } 
    vTaskDelayUntil(&nextWake, period);
  }
}
//Set-ID and Trim Servo functions
static bool handleSetIdCmd(const uint8_t* payload) {
  // Parse request: two u16 words, little-endian
  uint16_t w0 = (uint16_t)payload[0] | ((uint16_t)payload[1] << 8); // newId in low byte
  uint16_t w1 = (uint16_t)payload[2] | ((uint16_t)payload[3] << 8); // requested current limit
  uint8_t  newId    = (uint8_t)(w0 & 0xFF);
  uint16_t reqLimit = (w1 > 1023) ? 1023 : w1;
  // Invalid newId → ACK with oldId=0xFF, newId, cur=0
  if (newId > 253 || newId ==BROADCAST_ID) {
    uint8_t ack[6] = { 0xFF, 0x00, newId, 0x00, 0x00, 0x00 };
    sendAckFrame(SET_ID, ack, sizeof(ack));
    return true;
  }
  // Find any servo present
  uint8_t oldId = 0xFF;
  if (!scanRequireSingleServo(&oldId, newId)) return true; 
  
  if (newId != oldId) {
  if (gBusMux) xSemaphoreTake(gBusMux, portMAX_DELAY);
  (void)hlscl.Ping(newId);
  bool taken = !hlscl.getLastError();
  if (gBusMux) xSemaphoreGive(gBusMux);
  if (taken) {
    uint8_t ack14[14] = { oldId, 0x00, newId, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    sendAckFrame(SET_ID, ack14, sizeof(ack14));
    return true;
  }
  }
  if (gBusMux) xSemaphoreTake(gBusMux, portMAX_DELAY);
  (void)hlscl.unLockEprom(oldId);
  (void)hlscl.writeWord(oldId, REG_CURRENT_LIMIT, reqLimit);
  uint8_t targetId = oldId;
  if (newId != oldId) {
    (void)hlscl.writeByte(oldId, REG_ID, newId);   // REG_ID = 0x05
    targetId = newId;
  }
  (void)hlscl.LockEprom(targetId);
  
  // Read back limit for ACK
  uint16_t curLimitRead = 0;
  int rd = hlscl.readWord(targetId, REG_CURRENT_LIMIT);
  if (rd >= 0) curLimitRead = (uint16_t)rd;
  if (gBusMux) xSemaphoreGive(gBusMux);
  // Build 6-byte payload: oldId(LE16), newId(LE16), curLimit(LE16) and send
  uint8_t ack[6];
  ack[0] = oldId;                      // oldId lo
  ack[1] = 0x00;                       // oldId hi
  ack[2] = targetId;                   // newId lo
  ack[3] = 0x00;                       // newId hi
  ack[4] = (uint8_t)(curLimitRead & 0xFF);
  ack[5] = (uint8_t)((curLimitRead >> 8) & 0xFF);
  sendAckFrame(SET_ID, ack, sizeof(ack));
  return true;
}
static bool handleTrimCmd(const uint8_t* payload) {
  // Parse little-endian fields
  uint16_t rawCh  = (uint16_t)payload[0] | ((uint16_t)payload[1] << 8);
  uint16_t rawDeg = (uint16_t)payload[2] | ((uint16_t)payload[3] << 8);
  int      ch      = (int)rawCh;         // 0..6
  int16_t  degrees = (int16_t)rawDeg;    // signed degrees
  // Validate channel (0..6)
  if (ch < 0 || ch >= 7) {
    // Optionally send a NACK or silent-accept to preserve framing
    return true;
  }
  // Degrees -> counts (≈11.375 counts/deg), clamp to 0..4095
  int delta_counts = (int)((float)degrees * 11.375f);
  int new_ext = (int)sd[ch].extend_count + delta_counts;
  if (new_ext < 0)    new_ext = 0;
  if (new_ext > 4095) new_ext = 4095;
  sd[ch].extend_count = (uint16_t)new_ext;
  // Persist to NVS
  prefs.begin("hand", false);
  prefs.putInt(String("ext" + String(ch)).c_str(), sd[ch].extend_count);
  prefs.end();
  // ACK payload: ch (u16, LE), extend_count (u16, LE)
  uint8_t ack[4];
  ack[0] = (uint8_t)(ch & 0xFF);
  ack[1] = (uint8_t)((ch >> 8) & 0xFF);
  ack[2] = (uint8_t)(sd[ch].extend_count & 0xFF);
  ack[3] = (uint8_t)((sd[ch].extend_count >> 8) & 0xFF);
  sendAckFrame(TRIM, ack, sizeof(ack));   // 16 bytes on the wire
  return true;
}
static bool handleSetSpeedCmd(const uint8_t* payload)
{
  uint16_t rawId = (uint16_t)payload[0] | ((uint16_t)payload[1] << 8);
  uint16_t rawSpd = (uint16_t)payload[2] | ((uint16_t)payload[3] << 8);
  if (rawId >= 7) {
    //invalid index of servo actuator - So give error code (0–6 valid)
    uint8_t ack[4] = {0xFF, 0xFF, 0x00, 0x00};
    sendAckFrame(SET_SPE, ack, sizeof(ack));
    return true;
  }
  if (rawSpd > 32766) rawSpd = 32766;  // clamp
  g_speed[rawId] = rawSpd;
  // ACK back: servo id and speed
  uint8_t ack[4];
  ack[0] = (uint8_t)(rawId & 0xFF);
  ack[1] = (uint8_t)((rawId >> 8) & 0xFF);
  ack[2] = (uint8_t)(rawSpd & 0xFF);
  ack[3] = (uint8_t)((rawSpd >> 8) & 0xFF);
  sendAckFrame(SET_SPE, ack, sizeof(ack));
  return true;
}
static bool handleSetTorCmd(const uint8_t* payload)
{
  uint16_t rawId = (uint16_t)payload[0] | ((uint16_t)payload[1] << 8);
  uint16_t rawTor = (uint16_t)payload[2] | ((uint16_t)payload[3] << 8);
  // Validate ID range (0..6)
  if (rawId >= 7) {
    uint8_t ack[4] = {0xFF, 0xFF, 0x00, 0x00};  // invalid ID ack
    sendAckFrame(SET_TOR, ack, sizeof(ack));
    return true;
  }
  if (rawTor > 1023) rawTor = 1023;
  // Update the per-servo torque value
  g_torque[rawId] = rawTor;
  uint8_t ack[4];
  ack[0] = (uint8_t)(rawId & 0xFF);
  ack[1] = (uint8_t)((rawId >> 8) & 0xFF);
  ack[2] = (uint8_t)(rawTor & 0xFF);
  ack[3] = (uint8_t)((rawTor >> 8) & 0xFF);
  sendAckFrame(SET_TOR, ack, sizeof(ack));
  return true;
}

// ----- Returns true if a valid 16-byte frame was consumed and handled -----
// handleHostFrame已删除 - 由WebSocket命令处理替代
static bool handleHostFrame(uint8_t op) { return true; }

void setup() {
  // USB debug
  // Serial.begin(921600);  // 已注释，原用于USB调试
  delay(100);

  // Servo bus UART @ 1 Mbps
  Serial2.begin(1000000, SERIAL_8N1, SERIAL2_RX_PIN, SERIAL2_TX_PIN);
  hlscl.pSerial = &Serial2;

  resetSdToBaseline();
  prefs.begin("hand", false);
  loadManualExtendsFromNVS();
  #if defined(LEFT_HAND)
    Serial.println("[BOOT] Hand Type: LEFT_HAND");
  #elif defined(RIGHT_HAND)
    Serial.println("[BOOT] Hand Type: RIGHT_HAND");
  #else
    Serial.println("[BOOT] Hand Type: UNKNOWN");
  #endif

  esp_reset_reason_t reason = esp_reset_reason();
  bool do_homing = false;
  if (reason == ESP_RST_POWERON) {
    do_homing = true;
  }
  else if (reason == ESP_RST_BROWNOUT) {
    do_homing = true;   // Power-dipped but not fully to 0V, optional but recommended for safety
  }
  if (do_homing) {
    Serial.println("[BOOT] Power-on detected → homing");
    HOMING_start();
    saveExtendsToNVS();
  } else {
    Serial.println("[BOOT] Non-power reset → skipping homing");
  }
  // ---- Presence Check on Every Boot -----
  Serial.println("\n[Init] Pinging servos...");
  for (uint8_t i = 0; i < 7; ++i) {
    uint8_t id = SERVO_IDS[i];
    int resp = hlscl.Ping(id);
    if (!hlscl.getLastError()) {
      Serial.print("  ID "); Serial.print(id); Serial.println(": OK");
    } else {
      Serial.print("  ID "); Serial.print(id); Serial.println(": NO REPLY");
    }
  }
  //Syncreadbegin to Start the syncread
  hlscl.syncReadBegin(sizeof(SERVO_IDS), REG_BLOCK_LEN, /*rx_fix*/ 8);

  for (int i = 0; i < 7; ++i){
  Serial.printf("Servo %d dir=%d\n", i, sd[i].servo_direction);}
  //Initialisation of Mutex and Task serial pinned to Core 1
  gBusMux =xSemaphoreCreateMutex();
  gMetricsMux = xSemaphoreCreateMutex();
  xTaskCreatePinnedToCore(TaskSyncRead_Core1, "SyncRead", 4096, NULL, 1, NULL, 1); // run on Core1
}

// ============================================
// WiFi / WebSocket 初始化 (新增)
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

// WebSocket事件处理
void wsEventHandler(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
    switch (type) {
        case WStype_DISCONNECTED:
            DEBUG_PRINTF("[WS] Client %u disconnected\n", num);
            g_wsConnected = false;
            break;

        case WStype_CONNECTED:
            DEBUG_PRINTF("[WS] Client %u connected\n", num);
            g_wsConnected = true;
            blinkLED(2);
            break;

        case WStype_TEXT:
            DEBUG_PRINTF("[WS] Client %u sent: %s\n", num, payload);
            handleWsCommand((const char*)payload, length);
            g_lastWsActivity = millis();
            break;

        default:
            break;
    }
}

// 根据关节名称获取编号
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
        DEBUG_PRINTF("[CMD] JSON parse error: %s\n", error.c_str());
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
        DEBUG_PRINTF("[CMD] Joint %s -> %.1f°\n", jointId, clampedAngle);
        sendWsResponse(true, "Joint controlled");

    } else if (strcmp(type, "multi_joint_control") == 0) {
        // 多关节控制
        if (!doc["data"]["joints"].is<JsonArrayConst>()) {
            sendWsResponse(false, "Missing required fields in multi_joint_control");
            return;
        }

        JsonArrayConst joints = doc["data"]["joints"].as<JsonArrayConst>();
        int duration = doc["data"]["duration_ms"].as<int>();

        DEBUG_PRINTF("[CMD] Multi-joint control: %d joints\n", (int)joints.size());
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
        DEBUG_PRINTF("[CMD] Unknown command type: %s\n", type);
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

void loop() {
  // 处理WebSocket事件
  wsServer.loop();

  // ------ Soft Limit Check (保留原有功能) -------
  checkAndEnforceSoftLimits();

  delay(COMMAND_INTERVAL_MS);
}