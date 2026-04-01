/**
 * Aero Hand WebSocket C DLL Header
 *
 * License: Apache-2.0
 * Based on: TetherIA/aero-hand-open (https://github.com/TetherIA/aero-hand-open)
 *
 * Provides WebSocket communication for Aero Hand Open robotic hand
 * Compatible with C/C++, C#, Python (ctypes), and other languages
 */

#ifndef AERO_WS_DLL_H
#define AERO_WS_DLL_H

// ============================================
// Platform Export Macros
// ============================================

#ifdef _WIN32
    #ifdef AERO_WS_EXPORTS
        #define AERO_WS_API __declspec(dllexport)
    #else
        #define AERO_WS_API __declspec(dllimport)
    #endif
#else
    #define AERO_WS_API
#endif

// ============================================
// Error Codes
// ============================================

#define AERO_WS_OK              0
#define AERO_WS_ERROR          -1
#define AERO_WS_NOT_CONNECTED  -2
#define AERO_WS_TIMEOUT        -3
#define AERO_WS_INVALID_PARAM  -4
#define AERO_WS_SEND_ERROR     -5
#define AERO_WS_RECV_ERROR     -6

// ============================================
// Type Definitions
// ============================================

typedef void* AeroWSHandle;

typedef struct {
    char joint_id[32];
    float angle;
    float load;
} AeroJoint;

typedef struct {
    AeroJoint* joints;
    int count;
} AeroStates;

typedef void (*ConnectionCallback)(int connected, void* userdata);
typedef void (*MessageCallback)(const char* json_message, void* userdata);

// ============================================
// DLL Version
// ============================================

#ifdef __cplusplus
extern "C" {
#endif

AERO_WS_API const char* aero_ws_version(void);
AERO_WS_API const char* aero_ws_get_error(void);

// ============================================
// Connection Management
// ============================================

AERO_WS_API AeroWSHandle aero_ws_create(const char* host, int port);
AERO_WS_API void aero_ws_destroy(AeroWSHandle handle);
AERO_WS_API int aero_ws_connect(AeroWSHandle handle, int timeout_ms);
AERO_WS_API void aero_ws_disconnect(AeroWSHandle handle);
AERO_WS_API int aero_ws_is_connected(AeroWSHandle handle);
AERO_WS_API int aero_ws_start_receive(AeroWSHandle handle);
AERO_WS_API void aero_ws_stop_receive(AeroWSHandle handle);

// ============================================
// Callbacks
// ============================================

AERO_WS_API void aero_ws_set_connect_callback(
    AeroWSHandle handle,
    ConnectionCallback callback,
    void* userdata
);

AERO_WS_API void aero_ws_set_message_callback(
    AeroWSHandle handle,
    MessageCallback callback,
    void* userdata
);

// ============================================
// Control Commands
// ============================================

AERO_WS_API int aero_ws_set_joint(
    AeroWSHandle handle,
    const char* joint_id,
    float angle,
    int duration_ms
);

AERO_WS_API int aero_ws_set_joints(
    AeroWSHandle handle,
    AeroJoint* joints,
    int count,
    int duration_ms
);

AERO_WS_API int aero_ws_get_states(
    AeroWSHandle handle,
    AeroStates* states,
    int max_count
);

AERO_WS_API int aero_ws_homing(AeroWSHandle handle);
AERO_WS_API int aero_ws_send_raw(AeroWSHandle handle, const char* json);

#ifdef __cplusplus
}
#endif

#endif // AERO_WS_DLL_H
