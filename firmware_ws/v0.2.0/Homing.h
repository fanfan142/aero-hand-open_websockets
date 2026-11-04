#pragma once
#include <Arduino.h>
#include <HLSCL.h>

struct ServoData {
  uint16_t grasp_count;
  uint16_t extend_count;
  int8_t   servo_direction;
};

// Active (mutable) working copy used by the firmware:
extern ServoData sd[7];

// Immutable baselines (compile-time constants):
extern const ServoData sd_base_left[7];
extern const ServoData sd_base_right[7];

// Homing API
bool HOMING_isBusy();
void HOMING_start();

struct HomingGuardContext {
  uint32_t generation;
  uint16_t connHandle;
  uint32_t deadlineMs;
};
using HomingStartGuard = bool (*)(const HomingGuardContext& context);
// Called with the bus acquired, before baseline reset or motor I/O. False means no homing ran.
bool HOMING_startGuarded(HomingStartGuard guard, const HomingGuardContext& context);

// Utility
void resetSdToBaseline();                 // copy correct baseline -> sd

// Provided elsewhere:
extern HLSCL hlscl;
extern SemaphoreHandle_t gBusMux;

// Use the fixed, 7-element servo ID list from your main sketch:
extern const uint8_t SERVO_IDS[7];     // e.g., {0,1,2,3,4,5,6}
