/**
 * Aero Hand WebSocket Internal Header
 * 内部使用的数据结构和函数声明
 */

#ifndef AERO_WS_INTERNAL_H
#define AERO_WS_INTERNAL_H

#include <stdint.h>
#include <stdbool.h>
#include "aero_ws.h"

#ifdef _WIN32
#include <winsock2.h>
#include <windows.h>
#else
#include <pthread.h>
typedef int SOCKET;
#endif

// 关节数量定义
#define JOINT_COUNT 15

// WebSocket操作错误码
#define WS_OP_TEXT   0x01
#define WS_OP_CLOSE  0x08
#define WS_OP_PING   0x09
#define WS_OP_PONG   0x0A

/**
 * 内部上下文结构
 */
struct AeroWSContext {
    char host[128];
    int port;
    SOCKET sock;
    int connected;
    int running;

#ifdef _WIN32
    HANDLE thread;
    HANDLE states_mutex;
    HANDLE states_cv;
#else
    pthread_t thread;
    pthread_mutex_t states_mutex;
    pthread_cond_t states_cv;
#endif

    ConnectionCallback connect_cb;
    MessageCallback message_cb;
    void* connect_userdata;
    void* message_userdata;

    // get_states 同步机制
    bool states_pending;
    char states_response[4096];
    int states_response_len;
};

/**
 * 解析 JSON 关节状态响应
 * 返回解析出的关节数量
 */
int parse_states_response(const char* json, AeroStates* states, int max_count);

#endif // AERO_WS_INTERNAL_H
