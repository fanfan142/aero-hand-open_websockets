/**
 * Aero Hand WebSocket Internal Header
 * 内部使用的数据结构和函数声明
 */

#ifndef AERO_WS_INTERNAL_H
#define AERO_WS_INTERNAL_H

#include <stdint.h>

// 关节数量定义
#define JOINT_COUNT 15

// WebSocket操作错误码
#define WS_OP_TEXT   0x01
#define WS_OP_CLOSE  0x08

/**
 * 内部上下文结构
 */
struct AeroWSContext {
    char host[128];
    int port;
    int sock;
    int connected;
    int running;

#ifdef _WIN32
    void* thread;
#else
    void* thread;
#endif

    void (*connect_cb)(int, void*);
    void (*message_cb)(const char*, void*);
    void* userdata;
};

#endif // AERO_WS_INTERNAL_H
