/**
 * Aero Hand Servo Control Implementation
 */

#include <Arduino.h>
#include "servoControl.h"
#include "config.h"

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

static constexpr float MOTOR_PULLEY_RADIUS = 9.0f;
static constexpr float FINGER_MCP_FLEX_COEFF = 12.4912f;
static constexpr float FINGER_PIP_COEFF = 7.3211f;
static constexpr float FINGER_DIP_COEFF = 9.0f;
static constexpr float THUMB_FLEX_ABD_COEFF = 2.5f;
static constexpr float THUMB_FLEX_COEFF = 12.4931f;
static constexpr float THUMB_IP_ABD_COEFF = 2.5f;
static constexpr float THUMB_IP_FLEX_COEFF = 2.5f;
static constexpr float THUMB_IP_MCP_COEFF = 9.4372f;
static constexpr float THUMB_IP_COEFF = 12.5f;

static constexpr float ACTUATION_LOWER_LIMITS[SERVO_COUNT] = {0.0f, 0.0f, -15.2789f, 0.0f, 0.0f, 0.0f, 0.0f};
static constexpr float ACTUATION_UPPER_LIMITS[SERVO_COUNT] = {100.0f, 104.1250f, 247.1500f, 288.1603f, 288.1603f, 288.1603f, 288.1603f};

static uint16_t g_speed[SERVO_COUNT] = {32766, 32766, 32766, 32766, 32766, 32766, 32766};
static uint8_t g_accel[SERVO_COUNT] = {0, 0, 0, 0, 0, 0, 0};
static uint16_t g_torque[SERVO_COUNT] = {700, 700, 700, 700, 700, 700, 700};

HLSCL hlscl;
SemaphoreHandle_t gBusMux = nullptr;
const uint8_t SERVO_IDS[SERVO_COUNT] = {0, 1, 2, 3, 4, 5, 6};
ServoData sd[SERVO_COUNT];

ServoControl::ServoControl()
    : _txPin(-1)
    , _rxPin(-1)
    , _initialized(false)
    , _stateMux(nullptr) {
    for (int i = 0; i < JOINT_COUNT; i++) {
        _currentAngles[i] = 0;
    }
}

void ServoControl::begin(int txPin, int rxPin, long baudrate) {
    _txPin = txPin;
    _rxPin = rxPin;

    SERVO_SERIAL.begin(baudrate, SERIAL_8N1, rxPin, txPin);
    hlscl.pSerial = &SERVO_SERIAL;
    hlscl.IOTimeOut = 20;

    if (!gBusMux) {
        gBusMux = xSemaphoreCreateMutex();
    }
    if (!_stateMux) {
        _stateMux = xSemaphoreCreateMutex();
    }
    if (!gBusMux || !_stateMux) {
        DEBUG_PRINTLN("[SERVO] Failed to create mutex");
        return;
    }

    resetSdToBaseline();

    uint8_t ids[SERVO_COUNT];
    for (uint8_t i = 0; i < SERVO_COUNT; ++i) {
        ids[i] = SERVO_IDS[i];
        hlscl.EnableTorque(ids[i], 1);
        hlscl.ServoMode(ids[i]);
    }

    _initialized = true;
    DEBUG_PRINTF("[SERVO] Original HLSCL chain initialized, tx=%d rx=%d baud=%ld\n", txPin, rxPin, baudrate);

    homing();
}

bool ServoControl::setAngle(uint8_t jointId, int16_t angle, uint16_t duration) {
    if (!_initialized || jointId >= JOINT_COUNT) {
        DEBUG_PRINTF("[SERVO] Invalid or uninitialized setAngle, joint=%u\n", jointId);
        return false;
    }

    if (_stateMux) {
        xSemaphoreTake(_stateMux, portMAX_DELAY);
    }
    if (jointId == JOINT_THUMB_ROTATION) {
        _currentAngles[jointId] = constrain((int)angle, -300, 300);
    } else {
        _currentAngles[jointId] = constrain((int)angle, (int)SERVO_MIN_ANGLE, (int)SERVO_MAX_ANGLE);
    }
    if (_stateMux) {
        xSemaphoreGive(_stateMux);
    }
    return _applyJointState(duration);
}

bool ServoControl::setAngles(const JointAngle* joints, uint8_t count, uint16_t duration) {
    if (!_initialized || (count > 0 && joints == nullptr)) {
        return false;
    }

    if (_stateMux) {
        xSemaphoreTake(_stateMux, portMAX_DELAY);
    }
    for (uint8_t i = 0; i < count; i++) {
        if (joints[i].joint_id >= JOINT_COUNT) {
            continue;
        }
        if (joints[i].joint_id == JOINT_THUMB_ROTATION) {
            _currentAngles[joints[i].joint_id] = constrain((int)joints[i].angle, -300, 300);
        } else {
            _currentAngles[joints[i].joint_id] = constrain((int)joints[i].angle, (int)SERVO_MIN_ANGLE, (int)SERVO_MAX_ANGLE);
        }
    }
    if (_stateMux) {
        xSemaphoreGive(_stateMux);
    }

    return _applyJointState(duration);
}

int16_t ServoControl::getAngle(uint8_t jointId) {
    if (jointId >= JOINT_COUNT) {
        return 0;
    }

    int16_t value = 0;
    if (_stateMux) {
        xSemaphoreTake(_stateMux, portMAX_DELAY);
    }
    value = _currentAngles[jointId];
    if (_stateMux) {
        xSemaphoreGive(_stateMux);
    }
    return value;
}

void ServoControl::homing() {
    if (!_initialized || HOMING_isBusy()) {
        return;
    }

    DEBUG_PRINTLN("[SERVO] Running original homing sequence");
    HOMING_start();

    if (_stateMux) {
        xSemaphoreTake(_stateMux, portMAX_DELAY);
    }
    for (uint8_t i = 0; i < JOINT_COUNT; ++i) {
        _currentAngles[i] = 0;
    }
    if (_stateMux) {
        xSemaphoreGive(_stateMux);
    }
}

bool ServoControl::isConnected() {
    return _initialized && hlscl.pSerial != nullptr;
}

bool ServoControl::_applyJointState(uint16_t duration) {
    if (!_initialized) {
        return false;
    }

    uint16_t moveDuration = duration > 0 ? duration : 500;
    uint16_t speedValue = constrain((int)(moveDuration * 4), 200, 32766);

    int16_t jointSnapshot[JOINT_COUNT];
    _buildJointSnapshot(jointSnapshot);

    int16_t pos[SERVO_COUNT];
    uint8_t ids[SERVO_COUNT];
    for (uint8_t i = 0; i < SERVO_COUNT; ++i) {
        ids[i] = SERVO_IDS[i];
        pos[i] = (int16_t)_computeServoRawTarget(i, jointSnapshot);
        g_speed[i] = speedValue;
    }

    if (gBusMux) {
        xSemaphoreTake(gBusMux, portMAX_DELAY);
    }

    for (uint8_t i = 0; i < SERVO_COUNT; ++i) {
        hlscl.ServoMode(ids[i]);
    }
    hlscl.SyncWritePosEx(ids, SERVO_COUNT, pos, g_speed, g_accel, g_torque);

    if (gBusMux) {
        xSemaphoreGive(gBusMux);
    }

    DEBUG_PRINTF("[SERVO] Applied mapped 15->7 command, duration=%u\n", moveDuration);
    return true;
}

uint16_t ServoControl::_computeServoRawTarget(uint8_t servoIndex, const int16_t jointAngles[JOINT_COUNT]) const {
    const float thumbAbdDeg = jointAngles[JOINT_THUMB_ROTATION] / 10.0f;
    const float thumbFlexDeg = jointAngles[JOINT_THUMB_PROXIMAL] / 10.0f;
    const float thumbIpDeg = jointAngles[JOINT_THUMB_DISTAL] / 10.0f;

    const float thumbAbdAct = thumbAbdDeg;
    const float thumbFlexAct = (THUMB_FLEX_ABD_COEFF * thumbAbdDeg + THUMB_FLEX_COEFF * thumbFlexDeg) / MOTOR_PULLEY_RADIUS;
    const float thumbTendonAct = (THUMB_IP_ABD_COEFF * thumbAbdDeg - THUMB_IP_FLEX_COEFF * thumbFlexDeg + THUMB_IP_MCP_COEFF * thumbFlexDeg + THUMB_IP_COEFF * thumbIpDeg) / MOTOR_PULLEY_RADIUS;

    const auto fingerActuation = [&](uint8_t p, uint8_t m, uint8_t d) -> float {
        const float proximal = jointAngles[p] / 10.0f;
        const float middle = jointAngles[m] / 10.0f;
        const float distal = jointAngles[d] / 10.0f;
        return (FINGER_MCP_FLEX_COEFF * proximal + FINGER_PIP_COEFF * middle + FINGER_DIP_COEFF * distal) / MOTOR_PULLEY_RADIUS;
    };

    switch (servoIndex) {
        case 0:
            return _mapActuationToRaw(0, thumbAbdAct);
        case 1:
            return _mapActuationToRaw(1, thumbFlexAct);
        case 2:
            return _mapActuationToRaw(2, thumbTendonAct);
        case 3:
            return _mapActuationToRaw(3, fingerActuation(JOINT_INDEX_PROXIMAL, JOINT_INDEX_MIDDLE, JOINT_INDEX_DISTAL));
        case 4:
            return _mapActuationToRaw(4, fingerActuation(JOINT_MIDDLE_PROXIMAL, JOINT_MIDDLE_MIDDLE, JOINT_MIDDLE_DISTAL));
        case 5:
            return _mapActuationToRaw(5, fingerActuation(JOINT_RING_PROXIMAL, JOINT_RING_MIDDLE, JOINT_RING_DISTAL));
        case 6:
            return _mapActuationToRaw(6, fingerActuation(JOINT_PINKY_PROXIMAL, JOINT_PINKY_MIDDLE, JOINT_PINKY_DISTAL));
        default:
            return sd[0].extend_count;
    }
}

uint16_t ServoControl::_mapActuationToRaw(uint8_t servoIndex, float actuationDeg) const {
    float lower = ACTUATION_LOWER_LIMITS[servoIndex];
    float upper = ACTUATION_UPPER_LIMITS[servoIndex];
    float clamped = constrain(actuationDeg, lower, upper);
    float normalized = (clamped - lower) / (upper - lower);

    int32_t ext = sd[servoIndex].extend_count;
    int32_t gra = sd[servoIndex].grasp_count;
    int32_t raw32 = ext + (int32_t)(normalized * (gra - ext));

    if (raw32 < 0) raw32 = 0;
    if (raw32 > 4095) raw32 = 4095;
    return (uint16_t)raw32;
}

void ServoControl::_buildJointSnapshot(int16_t snapshot[JOINT_COUNT]) const {
    if (_stateMux) {
        xSemaphoreTake(_stateMux, portMAX_DELAY);
    }
    for (uint8_t i = 0; i < JOINT_COUNT; ++i) {
        snapshot[i] = _currentAngles[i];
    }
    if (_stateMux) {
        xSemaphoreGive(_stateMux);
    }
}
