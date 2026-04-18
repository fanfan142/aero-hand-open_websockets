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

// Base64解码（用于握手验证）
static int base64_decode(const char* in, unsigned char* out, int out_size) {
    static const unsigned char decode_table[256] = {
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0x3E,0xFF,0xFF,0xFF,0x3F,
        0x34,0x35,0x36,0x37,0x38,0x39,0x3A,0x3B,0x3C,0x3D,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0C,0x0D,0x0E,
        0x0F,0x10,0x11,0x12,0x13,0x14,0x15,0x16,0x17,0x18,0x19,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0x1A,0x1B,0x1C,0x1D,0x1E,0x1F,0x20,0x21,0x22,0x23,0x24,0x25,0x26,0x27,0x28,
        0x29,0x2A,0x2B,0x2C,0x2D,0x2E,0x2F,0x30,0x31,0x32,0x33,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
    };

    int j = 0;
    int val = 0;
    int bits = 0;

    for (int i = 0; in[i]; i++) {
        if (in[i] == '=') break;
        unsigned char c = (unsigned char)in[i];
        if (decode_table[c] == 0xFF) return -1;
        val = (val << 6) | decode_table[c];
        bits += 6;
        if (bits >= 8) {
            if (j >= out_size) return -1;
            out[j++] = (unsigned char)(val >> (bits - 8));
            bits -= 8;
        }
    }
    return j;
}

// 简单的 SHA1 计算（用于握手验证）
static void sha1_hash(const unsigned char* data, int len, unsigned char* out) {
    // RFC 3174 简化实现
    unsigned int h0 = 0x67452301;
    unsigned int h1 = 0xEFCDAB89;
    unsigned int h2 = 0x98BADCFE;
    unsigned int h3 = 0x10325476;
    unsigned int h4 = 0xC3D2E1F0;

    // 预处理：添加 bit '1' 和 bit '0'
    // 简化：使用固定长度的 copy 和简单填充
    unsigned char msg[64];
    int msg_len = len;
    memcpy(msg, data, len);
    msg[len] = 0x80;
    // 填充到 56 bytes (448 bits) 模 512
    int pad_len = (len < 56) ? (56 - len - 1) : (120 - len);
    memset(msg + len + 1, 0, pad_len);

    // 添加长度 (big endian)
    unsigned long long bits = (unsigned long long)len * 8;
    for (int i = 0; i < 8; i++) {
        msg[56 + i] = (unsigned char)((bits >> (56 - i * 8)) & 0xFF);
    }

    // 处理每个 64 字节块
    for (int chunk = 0; chunk < 64; chunk += 64) {
        unsigned int w[80];
        for (int i = 0; i < 16; i++) {
            w[i] = ((unsigned int)msg[chunk + i * 4] << 24) |
                   ((unsigned int)msg[chunk + i * 4 + 1] << 16) |
                   ((unsigned int)msg[chunk + i * 4 + 2] << 8) |
                   ((unsigned int)msg[chunk + i * 4 + 3]);
        }
        for (int i = 16; i < 80; i++) {
            unsigned int v = w[i-3] ^ w[i-8] ^ w[i-14] ^ w[i-16];
            w[i] = (v << 1) | (v >> 31);
        }

        unsigned int a = h0, b = h1, c = h2, d = h3, e = h4;

        for (int i = 0; i < 80; i++) {
            unsigned int f, k;
            if (i < 20) {
                f = (b & c) | ((~b) & d);
                k = 0x5A827999;
            } else if (i < 40) {
                f = b ^ c ^ d;
                k = 0x6ED9EBA1;
            } else if (i < 60) {
                f = (b & c) | (b & d) | (c & d);
                k = 0x8F1BBCDC;
            } else {
                f = b ^ c ^ d;
                k = 0xCA62C1D6;
            }

            unsigned int temp = ((a << 5) | (a >> 27)) + f + e + k + w[i];
            e = d;
            d = c;
            c = (b << 30) | (b >> 2);
            b = a;
            a = temp;
        }

        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e;
    }

    for (int i = 0; i < 4; i++) {
        out[i] = (unsigned char)((h0 >> (24 - i * 8)) & 0xFF);
        out[i + 4] = (unsigned char)((h1 >> (24 - i * 8)) & 0xFF);
        out[i + 8] = (unsigned char)((h2 >> (24 - i * 8)) & 0xFF);
        out[i + 12] = (unsigned char)((h3 >> (24 - i * 8)) & 0xFF);
        out[i + 16] = (unsigned char)((h4 >> (24 - i * 8)) & 0xFF);
    }
}

// 生成随机字节
// 简单的 JSON 解析辅助：跳过空白
static const char* json_skip_ws(const char* p) {
    while (*p && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) p++;
    return p;
}

// 解析 JSON 字符串字段（提取到 buffer，不含引号）
// 返回更新后的指针，-1 表示失败
static const char* json_parse_string(const char* p, char* out, int out_size) {
    p = json_skip_ws(p);
    if (*p != '"') return NULL;
    p++;
    int i = 0;
    while (*p && *p != '"' && i < out_size - 1) {
        if (*p == '\\' && p[1]) {
            p++;
            if (*p == 'n') out[i++] = '\n';
            else if (*p == 'r') out[i++] = '\r';
            else if (*p == 't') out[i++] = '\t';
            else out[i++] = *p;
        } else {
            out[i++] = *p;
        }
        p++;
    }
    if (*p != '"') return NULL;
    out[i] = '\0';
    return p + 1;
}

// 解析 JSON 数字字段
static int json_parse_number(const char* p, double* out) {
    p = json_skip_ws(p);
    char* end;
    *out = strtod(p, &end);
    return (end != p) ? 0 : -1;
}

// 解析关节状态数组: "joints": [{...}, {...}]
// 返回解析出的关节数量，-1 表示失败
// 注意: AeroStates 使用 AeroJoint* joints 指针，我们分配内存
static int json_parse_joints_array(const char* p, AeroStates* states, int max_count) {
    p = json_skip_ws(p);
    if (*p != '[') return -1;
    p++;
    int count = 0;

    // 分配临时数组（Caller负责释放）
    AeroJoint* joints = (AeroJoint*)malloc(sizeof(AeroJoint) * max_count);
    if (!joints) return -1;

    while (*p && *p != ']' && count < max_count) {
        p = json_skip_ws(p);
        if (*p != '{') break;
        p++;

        char joint_id[32] = {0};
        double angle = 0;
        double load = 0;
        int has_id = 0;

        while (*p && *p != '}') {
            p = json_skip_ws(p);
            if (strncmp(p, "\"joint_id\"", 9) == 0) {
                p += 9;
                p = json_skip_ws(p);
                if (*p == ':') p++;
                char id_buf[32];
                const char* next = json_parse_string(p, id_buf, sizeof(id_buf));
                if (next) {
                    strncpy(joint_id, id_buf, sizeof(joint_id) - 1);
                    has_id = 1;
                    p = next;
                }
            } else if (strncmp(p, "\"angle\"", 7) == 0) {
                p += 7;
                p = json_skip_ws(p);
                if (*p == ':') p++;
                json_parse_number(p, &angle);
                while (*p == '-' || (*p >= '0' && *p <= '9') || *p == '.') p++;
            } else if (strncmp(p, "\"load\"", 6) == 0) {
                p += 6;
                p = json_skip_ws(p);
                if (*p == ':') p++;
                json_parse_number(p, &load);
                while (*p == '-' || (*p >= '0' && *p <= '9') || *p == '.') p++;
            } else {
                p++;
            }
        }
        if (*p == '}') p++;

        if (has_id) {
            strncpy(joints[count].joint_id, joint_id, sizeof(joints[count].joint_id) - 1);
            joints[count].angle = (float)angle;
            joints[count].load = (float)load;
            count++;
        }

        p = json_skip_ws(p);
        if (*p == ',') p++;
    }

    if (*p == ']') p++;

    // 分配结果内存
    if (count > 0) {
        states->joints = (AeroJoint*)malloc(sizeof(AeroJoint) * count);
        if (states->joints) {
            memcpy(states->joints, joints, sizeof(AeroJoint) * count);
            states->count = count;
        } else {
            states->count = 0;
            count = -1;
        }
    } else {
        states->joints = NULL;
        states->count = 0;
    }
    free(joints);

    return count;
}

// 解析 states 响应 JSON: {"type": "states_response", "data": {"joints": [...]}}
int parse_states_response(const char* json, AeroStates* states, int max_count) {
    if (!json || !states) return -1;

    states->joints = NULL;
    states->count = 0;

    const char* p = json;

    // 找 "data" 字段
    while (*p) {
        if (strncmp(p, "\"data\"", 6) == 0) {
            p += 6;
            p = json_skip_ws(p);
            if (*p == ':') p++;
            p = json_skip_ws(p);

            // data 是对象，进去找 joints 数组
            if (*p == '{') {
                p++;
                while (*p && *p != '}') {
                    p = json_skip_ws(p);
                    if (strncmp(p, "\"joints\"", 8) == 0) {
                        p += 8;
                        p = json_skip_ws(p);
                        if (*p == ':') p++;
                        return json_parse_joints_array(p, states, max_count);
                    }
                    // 跳过其他字段
                    while (*p && *p != ',' && *p != '}') p++;
                    if (*p == ',') p++;
                }
            }
            break;
        }
        p++;
    }
    return -1;
}

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
    if (received <= 0) {
        set_error("Handshake failed - no response");
        return AERO_WS_ERROR;
    }
    response[received] = '\0';

    // 检查 HTTP 101 Switching Protocols
    if (strncmp(response, "HTTP/1.1 101", 12) != 0 &&
        strncmp(response, "HTTP/1.0 101", 12) != 0) {
        set_error("Handshake failed - server did not respond with 101");
        return AERO_WS_ERROR;
    }

    // 验证 Sec-WebSocket-Accept 头存在
    char* accept_header = strstr(response, "Sec-WebSocket-Accept:");
    if (accept_header == NULL) {
        set_error("Handshake failed - no Sec-WebSocket-Accept header");
        return AERO_WS_ERROR;
    }

    // RFC 6455: Accept = base64(SHA1(Sec-WebSocket-Key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
    const char* guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    unsigned char combined[64];
    int key_len = (int)strlen(key);
    memcpy(combined, key, key_len);
    memcpy(combined + key_len, guid, strlen(guid));

    unsigned char sha1_result[20];
    sha1_hash(combined, key_len + (int)strlen(guid), sha1_result);

    char expected_accept[32];
    base64_encode(sha1_result, 20, expected_accept);

    // 提取响应中的 Accept 值并比较
    char* accept_start = accept_header + 20;  // 跳过 "Sec-WebSocket-Accept: "
    while (*accept_start == ' ' || *accept_start == '\t') accept_start++;
    char* accept_end = accept_start;
    while (*accept_end && *accept_end != '\r' && *accept_end != '\n') accept_end++;
    int accept_len = (int)(accept_end - accept_start);

    if (accept_len != 28 || strncmp(accept_start, expected_accept, 28) != 0) {
        set_error("Handshake failed - Sec-WebSocket-Accept mismatch");
        return AERO_WS_ERROR;
    }

    return AERO_WS_OK;
}

// WebSocket发送帧 (客户端必须mask)
static int websocket_send(SOCKET sock, const char* data, int len) {
    if (len <= 0) len = (int)strlen(data);
    if (len <= 0) return AERO_WS_INVALID_PARAM;

    // 构造WebSocket帧 (client-to-server必须设置mask bit)
    // 帧头: 2字节(最小) + mask key(4字节) + payload
    // 最大可能: 2 + 8(extended len) + 4(mask) + len
    // 但我们限制payload为65535，所以最大帧: 2 + 2 + 4 + 65535 = 65543
    char frame[16];  // 仅存头部，实际数据直接send
    char mask_key[4];

    // 生成随机mask key
    generate_random_bytes((unsigned char*)mask_key, 4);

    frame[0] = 0x81;  // FIN + text frame (client-to-server)
    int mask_bit = 0x80;  // MASK bit 必须为1

    int header_len;
    if (len < 126) {
        frame[1] = mask_bit | (unsigned char)len;
        header_len = 2;
    } else if (len < 65536) {
        frame[1] = mask_bit | 126;
        frame[2] = (len >> 8) & 0xFF;
        frame[3] = len & 0xFF;
        header_len = 4;
    } else {
        // 超出支持范围
        set_error("Payload too large");
        return AERO_WS_SEND_ERROR;
    }

    // 发送帧头
    if (send(sock, frame, header_len, 0) != header_len) {
        return AERO_WS_SEND_ERROR;
    }

    // 发送mask key
    if (send(sock, mask_key, 4, 0) != 4) {
        return AERO_WS_SEND_ERROR;
    }

    // 发送mask后的payload (使用临时buffer避免修改原数据)
    char masked[4096];
    int chunk_size = sizeof(masked);
    int sent_total = 0;

    while (sent_total < len) {
        int chunk = (len - sent_total < chunk_size) ? (len - sent_total) : chunk_size;
        for (int i = 0; i < chunk; i++) {
            masked[i] = data[sent_total + i] ^ mask_key[i % 4];
        }
        int sent_now = send(sock, masked, chunk, 0);
        if (sent_now != chunk) {
            return AERO_WS_SEND_ERROR;
        }
        sent_total += chunk;
    }

    return AERO_WS_OK;
}

// WebSocket接收（服务器响应，无需mask）
static int websocket_recv(SOCKET sock, char* buffer, int buf_size, int timeout_ms) {
    if (buf_size <= 0) return AERO_WS_INVALID_PARAM;

    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(sock, &fds);

    struct timeval tv = { timeout_ms / 1000, (timeout_ms % 1000) * 1000 };
    int result = select(sock + 1, &fds, NULL, NULL, &tv);

    if (result <= 0) {
        return 0;  // 超时
    }

    char header[14];  // 2 + 8 (max extended length) + 4 (mask not present in server frames)
    int received = recv(sock, header, 2, 0);
    if (received != 2) return -1;

    int opcode = header[0] & 0x0F;
    int payload_len = header[1] & 0x7F;
    int offset = 2;

    // 处理扩展长度
    if (payload_len == 126) {
        received = recv(sock, header + 2, 2, 0);
        if (received != 2) return -1;
        payload_len = ((unsigned char)header[2] << 8) | (unsigned char)header[3];
        offset = 4;
    } else if (payload_len == 127) {
        received = recv(sock, header + 2, 8, 0);
        if (received != 8) return -1;
        // 检查是否超出范围
        uint64_t ext_len = 0;
        for (int i = 0; i < 8; i++) {
            ext_len = (ext_len << 8) | ((unsigned char)header[2 + i]);
        }
        if (ext_len > 0x7FFFFFFF) {
            set_error("Payload too large");
            return -1;
        }
        payload_len = (int)ext_len;
        offset = 10;
    }

    // 处理控制帧
    if (opcode == WS_OP_CLOSE) {
        // 收到 close 帧，回复 close
        char close_frame[2] = {0x88, 0x00};
        send(sock, close_frame, 2, 0);
        return -1;  // 表示连接关闭
    } else if (opcode == WS_OP_PING) {
        // 收到 ping，回复 pong
        char pong_frame[10];
        pong_frame[0] = 0x8A;  // pong + FIN
        if (payload_len < 126) {
            pong_frame[1] = (char)payload_len;
            // 读出 ping payload 并原样返回
            char tmp[125];
            int r = recv(sock, tmp, payload_len, 0);
            if (r != payload_len) return -1;
            memcpy(pong_frame + 2, tmp, payload_len);
            send(sock, pong_frame, 2 + payload_len, 0);
        } else {
            // payload >= 126 的 ping 很少见，简单处理
            char tmp[4096];
            int r = recv(sock, tmp, payload_len < (int)sizeof(tmp) ? payload_len : sizeof(tmp), 0);
            pong_frame[1] = payload_len < 126 ? (char)payload_len : 126;
            send(sock, pong_frame, 2, 0);
            if (r > 0) send(sock, tmp, r, 0);
        }
        return 0;  // 继续等待下一帧
    } else if (opcode != WS_OP_TEXT) {
        // 未知 opcode，跳过
        char tmp[4096];
        int to_read = payload_len < (int)sizeof(tmp) ? payload_len : (int)sizeof(tmp);
        int r = recv(sock, tmp, to_read, 0);
        if (r != to_read) return -1;
        if (payload_len > to_read) {
            // 跳过剩余
            int remaining = payload_len - to_read;
            char skip[1024];
            while (remaining > 0) {
                int skip_len = remaining < (int)sizeof(skip) ? remaining : (int)sizeof(skip);
                recv(sock, skip, skip_len, 0);
                remaining -= skip_len;
            }
        }
        return 0;  // 继续等待下一帧
    }

    // 检查 buffer 大小
    if (payload_len >= buf_size) {
        set_error("Buffer too small");
        // 跳过此帧
        char tmp[4096];
        int to_read = payload_len < (int)sizeof(tmp) ? payload_len : (int)sizeof(tmp);
        recv(sock, tmp, to_read, 0);
        if (payload_len > to_read) {
            int remaining = payload_len - to_read;
            char skip[1024];
            while (remaining > 0) {
                int skip_len = remaining < (int)sizeof(skip) ? remaining : (int)sizeof(skip);
                recv(sock, skip, skip_len, 0);
                remaining -= skip_len;
            }
        }
        return -1;
    }

    // 循环读取完整 payload
    int total_read = 0;
    while (total_read < payload_len) {
        int to_read = payload_len - total_read;
        int r = recv(sock, buffer + total_read, to_read, 0);
        if (r <= 0) return -1;
        total_read += r;
    }
    buffer[total_read] = '\0';

    return total_read;
}

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
    ctx->states_pending = false;

#ifdef _WIN32
    ctx->states_mutex = CreateMutex(NULL, FALSE, NULL);
    ctx->states_cv = CreateEvent(NULL, FALSE, FALSE, NULL);
#else
    pthread_mutex_init(&ctx->states_mutex, NULL);
    pthread_cond_init(&ctx->states_cv, NULL);
#endif

    return (AeroWSHandle)ctx;
}

void aero_ws_destroy(AeroWSHandle handle) {
    if (handle == NULL) return;

    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;
    aero_ws_disconnect(handle);

#ifdef _WIN32
    if (ctx->states_mutex) CloseHandle(ctx->states_mutex);
    if (ctx->states_cv) CloseHandle(ctx->states_cv);
#else
    pthread_mutex_destroy(&ctx->states_mutex);
    pthread_cond_destroy(&ctx->states_cv);
#endif

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
        ctx->connect_cb(1, ctx->connect_userdata);
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
        ctx->thread = 0;
    }
#endif

    if (ctx->sock != INVALID_SOCKET) {
        close_socket(ctx->sock);
        ctx->sock = INVALID_SOCKET;
    }

    if (ctx->connected && ctx->connect_cb) {
        ctx->connect_cb(0, ctx->connect_userdata);
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
    ctx->connect_userdata = userdata;
}

void aero_ws_set_message_callback(AeroWSHandle handle, MessageCallback callback, void* userdata) {
    if (handle == NULL) return;
    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;
    ctx->message_cb = callback;
    ctx->message_userdata = userdata;
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

    struct AeroWSContext* ctx = (struct AeroWSContext*)handle;

    if (!ctx->connected || ctx->sock == INVALID_SOCKET) {
        return AERO_WS_NOT_CONNECTED;
    }

    char json[256];
    snprintf(json, sizeof(json),
        "{\"type\":\"get_states\",\"timestamp\":%ld}",
        (long)time(NULL)
    );

    // 使用互斥锁同步响应
#ifdef _WIN32
    WaitForSingleObject(ctx->states_mutex, INFINITE);
#else
    pthread_mutex_lock(&ctx->states_mutex);
#endif

    // 标记等待状态
    ctx->states_pending = true;
    ctx->states_response[0] = '\0';
    ctx->states_response_len = 0;

    if (aero_ws_send_raw(handle, json) != AERO_WS_OK) {
#ifdef _WIN32
        ReleaseMutex(ctx->states_mutex);
#else
        pthread_mutex_unlock(&ctx->states_mutex);
#endif
        return AERO_WS_ERROR;
    }

    // 等待响应（最多5秒）
    int result = AERO_WS_ERROR;
    int waited = 0;
    const int timeout_ms = 5000;
    const int poll_interval = 50;

    while (waited < timeout_ms) {
#ifdef _WIN32
        DWORD wait_result = WaitForSingleObject(ctx->states_cv, poll_interval);
        if (wait_result == WAIT_OBJECT_0) {
            break;
        }
#else
        struct timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = poll_interval * 1000;

        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(ctx->sock, &fds);
        int sel = select(ctx->sock + 1, &fds, NULL, NULL, &tv);

        if (sel > 0) {
            // 有数据可读，尝试接收
            char tmp[4096];
            int r = recv(ctx->sock, tmp, sizeof(tmp) - 1, 0);
            if (r > 0) {
                tmp[r] = '\0';
                // 检查是否是 states_response 类型
                if (strstr(tmp, "\"type\"") && strstr(tmp, "\"states_response\"")) {
                    // 追加到响应缓冲区
                    int copy_len = r < (int)sizeof(ctx->states_response) - ctx->states_response_len - 1
                                   ? r : (int)sizeof(ctx->states_response) - ctx->states_response_len - 1;
                    memcpy(ctx->states_response + ctx->states_response_len, tmp, copy_len);
                    ctx->states_response_len += copy_len;
                    ctx->states_response[ctx->states_response_len] = '\0';
                }
            }
        }

        pthread_mutex_lock(&ctx->states_mutex);
        if (!ctx->states_pending) {
            pthread_mutex_unlock(&ctx->states_mutex);
            break;
        }
        pthread_mutex_unlock(&ctx->states_mutex);
#endif
        waited += poll_interval;
    }

    if (ctx->states_pending && waited >= timeout_ms) {
        // 超时，取消等待
        ctx->states_pending = false;
#ifdef _WIN32
        ReleaseMutex(ctx->states_mutex);
#else
        pthread_mutex_unlock(&ctx->states_mutex);
#endif
        set_error("get_states timeout");
        return AERO_WS_ERROR;
    }

    // 解析响应
    if (ctx->states_response_len > 0) {
        result = parse_states_response(ctx->states_response, states, max_count);
        if (result < 0) {
            set_error("Failed to parse states response");
            result = AERO_WS_ERROR;
        }
    } else {
        set_error("No states response received");
        result = AERO_WS_ERROR;
    }

#ifdef _WIN32
    ReleaseMutex(ctx->states_mutex);
#else
    pthread_mutex_unlock(&ctx->states_mutex);
#endif

    return result;
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
            ctx->message_cb(buffer, ctx->message_userdata);
        } else if (len < 0) {
            break;
        }
    }

    ctx->running = 0;
    ctx->connected = 0;

    if (ctx->connect_cb) {
        ctx->connect_cb(0, ctx->connect_userdata);
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
