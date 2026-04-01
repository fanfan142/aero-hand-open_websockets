/**
 * Aero Hand WebSocket C DLL Header
 * C语言动态库接口定义
 *
 * 提供给C#/C++/Python等语言调用
 */

#ifndef AERO_WS_DLL_H
#define AERO_WS_DLL_H

// ============================================
// 平台导出宏
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
// 错误码定义
// ============================================

#define AERO_WS_OK              0
#define AERO_WS_ERROR          -1
#define AERO_WS_NOT_CONNECTED  -2
#define AERO_WS_TIMEOUT        -3
#define AERO_WS_INVALID_PARAM  -4
#define AERO_WS_SEND_ERROR     -5
#define AERO_WS_RECV_ERROR     -6

// ============================================
// 类型定义
// ============================================

// 句柄类型
typedef void* AeroWSHandle;

// 关节数据
typedef struct {
    char joint_id[32];      // 关节名称，如 "index_proximal"
    float angle;            // 角度值
    float load;            // 负载 (0.0-1.0)
} AeroJoint;

// 关节状态数组
typedef struct {
    AeroJoint* joints;
    int count;
} AeroStates;

// 连接状态回调
typedef void (*ConnectionCallback)(int connected, void* userdata);

// 消息接收回调
typedef void (*MessageCallback)(const char* json_message, void* userdata);

// ============================================
// DLL版本信息
// ============================================

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 获取DLL版本
 * @return 版本字符串
 */
AERO_WS_API const char* aero_ws_version(void);

/**
 * 获取最后错误描述
 * @return 错误信息字符串
 */
AERO_WS_API const char* aero_ws_get_error(void);

// ============================================
// 连接管理
// ============================================

/**
 * 创建WebSocket连接
 * @param host 服务器地址，如 "192.168.1.100"
 * @param port 端口号，如 8765
 * @return 连接句柄，失败返回NULL
 */
AERO_WS_API AeroWSHandle aero_ws_create(const char* host, int port);

/**
 * 销毁连接句柄
 * @param handle 连接句柄
 */
AERO_WS_API void aero_ws_destroy(AeroWSHandle handle);

/**
 * 连接到服务器（同步）
 * @param handle 连接句柄
 * @param timeout_ms 超时时间(毫秒)
 * @return AERO_WS_OK成功，其他失败
 */
AERO_WS_API int aero_ws_connect(AeroWSHandle handle, int timeout_ms);

/**
 * 断开连接
 * @param handle 连接句柄
 */
AERO_WS_API void aero_ws_disconnect(AeroWSHandle handle);

/**
 * 检查是否已连接
 * @param handle 连接句柄
 * @return 1已连接，0未连接
 */
AERO_WS_API int aero_ws_is_connected(AeroWSHandle handle);

/**
 * 启动异步接收循环（在独立线程中）
 * @param handle 连接句柄
 * @return AERO_WS_OK成功
 */
AERO_WS_API int aero_ws_start_receive(AeroWSHandle handle);

/**
 * 停止接收循环
 * @param handle 连接句柄
 */
AERO_WS_API void aero_ws_stop_receive(AeroWSHandle handle);

// ============================================
// 设置回调
// ============================================

/**
 * 设置连接状态改变回调
 * @param handle 连接句柄
 * @param callback 回调函数
 * @param userdata 用户数据
 */
AERO_WS_API void aero_ws_set_connect_callback(
    AeroWSHandle handle,
    ConnectionCallback callback,
    void* userdata
);

/**
 * 设置消息接收回调
 * @param handle 连接句柄
 * @param callback 回调函数
 * @param userdata 用户数据
 */
AERO_WS_API void aero_ws_set_message_callback(
    AeroWSHandle handle,
    MessageCallback callback,
    void* userdata
);

// ============================================
// 控制指令
// ============================================

/**
 * 控制单关节
 * @param handle 连接句柄
 * @param joint_id 关节名称
 * @param angle 目标角度
 * @param duration_ms 动作时间(毫秒)
 * @return AERO_WS_OK成功
 */
AERO_WS_API int aero_ws_set_joint(
    AeroWSHandle handle,
    const char* joint_id,
    float angle,
    int duration_ms
);

/**
 * 控制多关节同步
 * @param handle 连接句柄
 * @param joints 关节数组
 * @param count 关节数量
 * @param duration_ms 动作时间(毫秒)
 * @return AERO_WS_OK成功
 */
AERO_WS_API int aero_ws_set_joints(
    AeroWSHandle handle,
    AeroJoint* joints,
    int count,
    int duration_ms
);

/**
 * 获取所有关节状态
 * @param handle 连接句柄
 * @param states 输出状态数组（需先分配内存）
 * @param max_count 最大关节数量
 * @return 实际获取的关节数量
 */
AERO_WS_API int aero_ws_get_states(
    AeroWSHandle handle,
    AeroStates* states,
    int max_count
);

/**
 * 发送归零指令
 * @param handle 连接句柄
 * @return AERO_WS_OK成功
 */
AERO_WS_API int aero_ws_homing(AeroWSHandle handle);

/**
 * 发送原始JSON指令
 * @param handle 连接句柄
 * @param json JSON格式字符串
 * @return AERO_WS_OK成功
 */
AERO_WS_API int aero_ws_send_raw(AeroWSHandle handle, const char* json);

#ifdef __cplusplus
}
#endif

#endif // AERO_WS_DLL_H
