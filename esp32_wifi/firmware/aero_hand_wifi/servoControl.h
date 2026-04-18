/**
 * Aero Hand Servo Control Header
 * 舵机控制接口
 */

#ifndef SERVOCONTROL_H
#define SERVOCONTROL_H

#include <Arduino.h>
#include "config.h"
#include "HLSCL.h"
#include "Homing.h"

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

// 关节角度结构
struct JointAngle {
    uint8_t joint_id;
    int16_t angle;      // 实际角度*10，例如90.0° -> 900
};

// 舵机控制类
class ServoControl {
public:
    ServoControl();

    /**
     * 初始化串口
     * @param txPin TX引脚
     * @param rxPin RX引脚
     * @param baudrate 波特率
     */
    void begin(int txPin, int rxPin, long baudrate);

    /**
     * 设置单个关节角度
     * @param jointId 关节ID (0-14)
     * @param angle 角度值 (0-900，对应0°-90°)
     * @param duration 动作时间(ms)
     * @return 是否成功
     */
    bool setAngle(uint8_t jointId, int16_t angle, uint16_t duration = 500);

    /**
     * 同步设置多个关节角度
     * @param joints 关节角度数组
     * @param count 关节数量
     * @param duration 动作时间(ms)
     * @return 是否成功
     */
    bool setAngles(const JointAngle* joints, uint8_t count, uint16_t duration = 500);

    /**
     * 获取当前关节角度
     * @param jointId 关节ID
     * @return 当前角度
     */
    int16_t getAngle(uint8_t jointId);

    /**
     * 所有关节归零
     */
    void homing();

    /**
     * 检查串口是否可用
     */
    bool isConnected();

private:
    int _txPin;
    int _rxPin;
    bool _initialized;
    SemaphoreHandle_t _stateMux;
    int16_t _currentAngles[JOINT_COUNT];  // 当前角度记录 (0.1°)

    bool _applyJointState(uint16_t duration);
    uint16_t _computeServoRawTarget(uint8_t servoIndex, const int16_t jointAngles[JOINT_COUNT]) const;
    uint16_t _mapActuationToRaw(uint8_t servoIndex, float actuationDeg) const;
    void _buildJointSnapshot(int16_t snapshot[JOINT_COUNT]) const;
};

#endif // SERVOCONTROL_H
