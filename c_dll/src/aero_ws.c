/**
 * Aero Hand WebSocket C DLL Implementation
 *
 * License: Apache-2.0
 * Based on: TetherIA/aero-hand-open (https://github.com/TetherIA/aero-hand-open)
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
    #include <winsock2.h>
    #include <ws2tcpip.h>
    #pragma comment(lib, "ws2_32.lib")
    typedef int socklen_t;
#else
    #include <sys/socket.h>
    #include <netinet/in.h>
    #include <arpa/inet.h>
    #include <netdb.h>
    #include <unistd.h>
    #include <pthread.h>
    typedef int SOCKET;
    #define SOCKET_ERROR -1
    #define INVALID_SOCKET -1
#endif

#include "aero_ws.h"
#include "../include/aero_ws_internal.h"

// ============================================
// 全局数据
// ============================================

static char g_error_msg[256] = {0};

// ============================================
// 内部函数
// ============================================

static void set_error(const char* msg) {
    strncpy(g_error_msg, msg, sizeof(g_error_msg) - 1);
}

static SOCKET create_socket(void) {
#ifdef _WIN32
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
#endif
    return socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
}

static void close_socket(SOCKET sock) {
#ifdef _WIN32
    closesocket(sock);
    WSACleanup();
#else
    close(sock);
#endif
}

static int connect_socket(SOCKET sock, const char* host, int port, int timeout_ms) {
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((short)port);
    addr.sin_addr.s_addr = inet_addr(host);

    if (addr.sin_addr.s_addr == INADDR_NONE) {
        // 尝试解析主机名
        struct hostent* he = gethostbyname(host);
        if (he == NULL) {
            set_error("Failed to resolve host");
            return AERO_WS_ERROR;
        }
        memcpy(&addr.sin_addr, he->h_addr_list[0], he->h_length);
    }

#ifdef _WIN32
    u_long mode = 1;
    ioctlsocket(sock, FIONBIO, &mode);

    int result = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    if (result == SOCKET_ERROR) {
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(sock, &fds);
        struct timeval tv = { timeout_ms / 1000, (timeout_ms % 1000) * 1000 };
        result = select(0, NULL, &fds, NULL, &tv);
        if (result <= 0) {
            set_error("Connection timeout");
            return AERO_WS_TIMEOUT;
        }
    }
#else
    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) == SOCKET_ERROR) {
        set_error("Connection failed");
        return AERO_WS_ERROR;
    }
#endif

    return AERO_WS_OK;
}

// Base64编码表
static const char base64_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

// 简单的Base64编码（用于WebSocket握手）
static void base64_encode(const unsigned char* in, int len, char* out) {
    int i, j;
    for (i = 0, j = 0; i < len; i += 3) {
        int a = in[i];
        int b = (i + 1 < len) ? in[i + 1] : 0;
        int c = (i + 2 < len) ? in[i + 2] : 0;

        out[j++] = base64_table[(a >> 2) & 0x3F];
        out[j++] = base64_table[((a << 4) | (b >> 4)) & 0x3F];
        out[j++] = (i + 1 < len) ? base64_table[((b << 2) | (c >> 6)) & 0x3F] : '=';
        out[j++] = (i + 2 < len) ? base64_table[c & 0x3F] : '=';
    }
    out[j] = '\0';
}

// 生成随机字节
static void generate_random_bytes(unsigned char* buf, int len) {
    int i;
    // 初始化随机种子（仅在首次调用时）
    static int seeded = 0;
    if (!seeded) {
        srand((unsigned int)time(NULL));
        seeded = 1;
    }
    for (i = 0; i < len; i++) {
        buf[i] = (unsigned char)(rand() % 256);
    }
}

// WebSocket握手
static int websocket_handshake(SOCKET sock, const char* host, int port) {
    char request[512];
    char response[512];
    // Base64编码16字节需要 ceil(16/3)*4 = 22字符，加上填充和null terminator
    char key[30];  // 留有余量
    char accept[32];

    // 生成随机key
    unsigned char random_bytes[16];
    generate_random_bytes(random_bytes, 16);
    base64_encode(random_bytes, 16, key);

    snprintf(request, sizeof(request),
        "GET / HTTP/1.1\r\n"
        "Host: %s:%d\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "Origin: aero-hand\r\n"
        "\r\n",
        host, port, key
    );

    if (send(sock, request, strlen(request), 0) == SOCKET_ERROR) {
        set_error("Failed to send handshake");
        return AERO_WS_ERROR;
    }

    int received = recv(sock, response, sizeof(response) - 1, 0);
    if (received <= 0 || strstr(response, "101") == NULL) {
        set_error("Handshake failed - server did not respond with 101");
        return AERO_WS_ERROR;
    }

    // 验证Sec-WebSocket-Accept
    if (strstr(response, "Sec-WebSocket-Accept") == NULL) {
        set_error("Handshake failed - no Accept header");
        return AERO_WS_ERROR;
    }

    return AERO_WS_OK;
}

// WebSocket发送帧
static int websocket_send(SOCKET sock, const char* data, int len) {
    if (len <= 0) len = strlen(data);

    // 构造WebSocket帧
    char frame[4096];
    frame[0] = 0x81;  // FIN + text frame
    frame[1] = (len < 126) ? len : 126;

    int frame_len = 2;
    if (len >= 126) {
        frame[1] = 126;
        frame[2] = (len >> 8) & 0xFF;
        frame[3] = len & 0xFF;
        frame_len = 4;
    }

    memcpy(frame + frame_len, data, len);
    frame_len += len;

    int sent = send(sock, frame, frame_len, 0);
    return (sent == frame_len) ? AERO_WS_OK : AERO_WS_SEND_ERROR;
}

// WebSocket接收（简单版本，不解析掩码）
static int websocket_recv(SOCKET sock, char* buffer, int buf_size, int timeout_ms) {
    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(sock, &fds);

    struct timeval tv = { timeout_ms / 1000, (timeout_ms % 1000) * 1000 };
    int result = select(sock + 1, &fds, NULL, NULL, &tv);

    if (result <= 0) {
        return 0;  // 超时
    }

    char header[16];
    int received = recv(sock, header, sizeof(header), 0);
    if (received < 2) return -1;

    int payload_len = header[1] & 0x7F;
    int offset = 2;

    if (payload_len == 126) {
        received = recv(sock, header + 2, 2, 0);
        payload_len = (header[2] << 8) | header[3];
        offset = 4;
    } else if (payload_len == 127) {
        received = recv(sock, header + 2, 8, 0);
        payload_len = 0;
        for (int i = 0; i < 8; i++) {
            payload_len = (payload_len << 8) | (header[2 + i] & 0xFF);
        }
        offset = 10;
    }

    if (payload_len >= buf_size) {
        set_error("Buffer too small");
        return -1;
    }

    received = recv(sock, buffer, payload_len, 0);
    buffer[received] = '\0';

    return received;
}

// ============================================
// 内部上下文结构
// ============================================

struct AeroWSContext {
    char host[128];
    int port;
    SOCKET sock;
    int connected;
    int running;  // 接收线程运行标志

#ifdef _WIN32
    HANDLE thread;
#else
    pthread_t thread;
#endif

    ConnectionCallback connect_cb;
    MessageCallback message_cb;
    void* userdata;
};

// ============================================
// API实现
// ============================================

const char* aero_ws_version(void) {
    return "1.0.0";
}

const char* aero_ws_get_error(void) {
    return g_error_msg;
}

AeroWSHandle aero_ws_create(const char* host, int port) {
    if (host == NULL || port <= 0) {
        set_error("Invalid parameters");
        return NULL;
    }

    struct AeroWSContext* ctx = (struct AeroWSContext*)malloc(sizeof(struct AeroWSContext));
    if (ctx == NULL) {
        set_error("Out of memory");
        return NULL;
    }

    memset(ctx, 0, sizeof(struct AeroWSContext));
    strncpy(ctx->host, host, sizeof(ctx->host) - 1);
    ctx->port = port;
    ctx->sock = INVALID_SOCKET;
    ctx->connected = 0;
    ctx->running = 0;

    return (AeroWSHandle)ctx;
}

void aero_ws_destroy(AeroWSHandle handle) {
    if (handle == NULL) return;

    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;
    aero_ws_disconnect(handle);
    free(ctx);
}

int aero_ws_connect(AeroWSHandle handle, int timeout_ms) {
    if (handle == NULL) return AERO_WS_INVALID_PARAM;

    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;

    ctx->sock = create_socket();
    if (ctx->sock == INVALID_SOCKET) {
        set_error("Failed to create socket");
        return AERO_WS_ERROR;
    }

    int result = connect_socket(ctx->sock, ctx->host, ctx->port, timeout_ms);
    if (result != AERO_WS_OK) {
        close_socket(ctx->sock);
        ctx->sock = INVALID_SOCKET;
        return result;
    }

    result = websocket_handshake(ctx->sock, ctx->host, ctx->port);
    if (result != AERO_WS_OK) {
        close_socket(ctx->sock);
        ctx->sock = INVALID_SOCKET;
        return result;
    }

    ctx->connected = 1;

    if (ctx->connect_cb) {
        ctx->connect_cb(1, ctx->userdata);
    }

    return AERO_WS_OK;
}

void aero_ws_disconnect(AeroWSHandle handle) {
    if (handle == NULL) return;

    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;

    ctx->running = 0;

#ifdef _WIN32
    if (ctx->thread) {
        WaitForSingleObject(ctx->thread, 1000);
        CloseHandle(ctx->thread);
        ctx->thread = NULL;
    }
#else
    if (ctx->thread) {
        pthread_join(ctx->thread, NULL);
        ctx->thread = NULL;
    }
#endif

    if (ctx->sock != INVALID_SOCKET) {
        close_socket(ctx->sock);
        ctx->sock = INVALID_SOCKET;
    }

    if (ctx->connected && ctx->connect_cb) {
        ctx->connect_cb(0, ctx->userdata);
    }

    ctx->connected = 0;
}

int aero_ws_is_connected(AeroWSHandle handle) {
    if (handle == NULL) return 0;
    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;
    return ctx->connected;
}

void aero_ws_set_connect_callback(AeroWSHandle handle, ConnectionCallback callback, void* userdata) {
    if (handle == NULL) return;
    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;
    ctx->connect_cb = callback;
    ctx->userdata = userdata;
}

void aero_ws_set_message_callback(AeroWSHandle handle, MessageCallback callback, void* userdata) {
    if (handle == NULL) return;
    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;
    ctx->message_cb = callback;
}

int aero_ws_set_joint(AeroWSHandle handle, const char* joint_id, float angle, int duration_ms) {
    if (handle == NULL) return AERO_WS_INVALID_PARAM;

    char json[512];
    snprintf(json, sizeof(json),
        "{\"type\":\"joint_control\",\"timestamp\":%ld,\"data\":{\"joint_id\":\"%s\",\"angle\":%.1f,\"duration_ms\":%d}}",
        (long)time(NULL), joint_id, angle, duration_ms
    );

    return aero_ws_send_raw(handle, json);
}

int aero_ws_set_joints(AeroWSHandle handle, AeroJoint* joints, int count, int duration_ms) {
    if (handle == NULL || joints == NULL || count <= 0) return AERO_WS_INVALID_PARAM;

    char json[4096];
    char* p = json;
    int len;

    p += snprintf(p, 256, "{\"type\":\"multi_joint_control\",\"timestamp\":%ld,\"data\":{\"joints\":[", (long)time(NULL));

    for (int i = 0; i < count; i++) {
        p += snprintf(p, 128, "%s{\"joint_id\":\"%s\",\"angle\":%.1f}",
            (i > 0) ? "," : "", joints[i].joint_id, joints[i].angle);
    }

    snprintf(p, 256, "],\"duration_ms\":%d}}", duration_ms);

    return aero_ws_send_raw(handle, json);
}

int aero_ws_get_states(AeroWSHandle handle, AeroStates* states, int max_count) {
    if (handle == NULL || states == NULL) return AERO_WS_INVALID_PARAM;

    char json[256];
    snprintf(json, sizeof(json),
        "{\"type\":\"get_states\",\"timestamp\":%ld}",
        (long)time(NULL)
    );

    if (aero_ws_send_raw(handle, json) != AERO_WS_OK) {
        return AERO_WS_ERROR;
    }

    // 简化实现，实际应等待响应
    return 0;
}

int aero_ws_homing(AeroWSHandle handle) {
    if (handle == NULL) return AERO_WS_INVALID_PARAM;

    char json[256];
    snprintf(json, sizeof(json),
        "{\"type\":\"homing\",\"timestamp\":%ld}",
        (long)time(NULL)
    );

    return aero_ws_send_raw(handle, json);
}

int aero_ws_send_raw(AeroWSHandle handle, const char* json) {
    if (handle == NULL || json == NULL) return AERO_WS_INVALID_PARAM;

    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;

    if (!ctx->connected || ctx->sock == INVALID_SOCKET) {
        return AERO_WS_NOT_CONNECTED;
    }

    if (websocket_send(ctx->sock, json, strlen(json)) != AERO_WS_OK) {
        return AERO_WS_SEND_ERROR;
    }

    return AERO_WS_OK;
}

// 接收线程函数（Windows）
#ifdef _WIN32
static DWORD WINAPI receive_thread(LPVOID param) {
#else
static void* receive_thread(void* param) {
#endif
    struct AeroWSContext* ctx = (struct AeroWSContext*)param;
    char buffer[4096];

    while (ctx->running && ctx->connected) {
        int len = websocket_recv(ctx->sock, buffer, sizeof(buffer) - 1, 100);
        if (len > 0 && ctx->message_cb) {
            ctx->message_cb(buffer, ctx->userdata);
        } else if (len < 0) {
            break;
        }
    }

    ctx->running = 0;
    ctx->connected = 0;

    if (ctx->connect_cb) {
        ctx->connect_cb(0, ctx->userdata);
    }

#ifdef _WIN32
    return 0;
#else
    return NULL;
#endif
}

int aero_ws_start_receive(AeroWSHandle handle) {
    if (handle == NULL) return AERO_WS_INVALID_PARAM;

    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;
    if (!ctx->connected) return AERO_WS_NOT_CONNECTED;

    ctx->running = 1;

#ifdef _WIN32
    ctx->thread = CreateThread(NULL, 0, receive_thread, ctx, 0, NULL);
    if (ctx->thread == NULL) {
        ctx->running = 0;
        return AERO_WS_ERROR;
    }
#else
    if (pthread_create(&ctx->thread, NULL, receive_thread, ctx) != 0) {
        ctx->running = 0;
        return AERO_WS_ERROR;
    }
#endif

    return AERO_WS_OK;
}

void aero_ws_stop_receive(AeroWSHandle handle) {
    if (handle == NULL) return;
    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;
    ctx->running = 0;
}
