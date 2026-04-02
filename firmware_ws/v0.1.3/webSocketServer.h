/**
 * Aero Hand WebSocket Server Header
 * WebSocket服务处理
 */

#ifndef WEBSOCKETSERVER_H
#define WEBSOCKETSERVER_H

#include <Arduino.h>
#include <WebSocketsServer.h>
#include <ArduinoJson.h>

// WebSocket事件回调类型
typedef void (*WSMessageCallback)(const char* payload, size_t length);
typedef void (*WSConnectCallback)(uint8_t num);
typedef void (*WSDisconnectCallback)(uint8_t num);

class AeroWebSocketServer {
public:
    AeroWebSocketServer();

    /**
     * 初始化WebSocket服务
     * @param port 监听端口
     */
    void begin(int port = 8765);

    /**
     * 停止服务
     */
    void end();

    /**
     * 处理WebSocket事件（需在loop中调用）
     */
    void loop();

    /**
     * 设置消息回调
     */
    void onMessage(WSMessageCallback callback);

    /**
     * 设置连接回调
     */
    void onConnect(WSConnectCallback callback);

    /**
     * 设置断开回调
     */
    void onDisconnect(WSDisconnectCallback callback);

    /**
     * 发送文本消息给所有客户端
     */
    void broadcastText(String message);

    /**
     * 发送文本消息给指定客户端
     */
    void sendText(uint8_t num, String message);

    /**
     * 检查是否有客户端连接
     */
    bool hasClients();

    /**
     * 获取客户端数量
     */
    int getClientCount();

private:
    WebSocketsServer _wsServer;
    int _port;
    WSMessageCallback _messageCallback;
    WSConnectCallback _connectCallback;
    WSDisconnectCallback _disconnectCallback;

    // 内部事件处理
    void _wsEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length);
};

#endif // WEBSOCKETSERVER_H
