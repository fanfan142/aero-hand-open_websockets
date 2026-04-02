/**
 * Aero Hand WebSocket Server Implementation
 */

#include <Arduino.h>
#include "webSocketServer.h"
#include "config.h"

AeroWebSocketServer::AeroWebSocketServer()
    : _wsServer(WS_PORT)
    , _port(WS_PORT)
    , _messageCallback(nullptr)
    , _connectCallback(nullptr)
    , _disconnectCallback(nullptr)
{
}

void AeroWebSocketServer::begin(int port) {
    _port = port;
    _wsServer.onEvent([this](uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
        this->_wsEvent(num, type, payload, length);
    });
    _wsServer.begin();

    DEBUG_PRINTF("[WS] WebSocket server started on port %d\n", _port);
}

void AeroWebSocketServer::end() {
    _wsServer.disconnect();
    _wsServer.close();
}

void AeroWebSocketServer::loop() {
    _wsServer.loop();
}

void AeroWebSocketServer::onMessage(WSMessageCallback callback) {
    _messageCallback = callback;
}

void AeroWebSocketServer::onConnect(WSConnectCallback callback) {
    _connectCallback = callback;
}

void AeroWebSocketServer::onDisconnect(WSDisconnectCallback callback) {
    _disconnectCallback = callback;
}

void AeroWebSocketServer::broadcastText(String message) {
    _wsServer.broadcastTXT(message.c_str());
}

void AeroWebSocketServer::sendText(uint8_t num, String message) {
    _wsServer.sendTXT(num, message.c_str());
}

bool AeroWebSocketServer::hasClients() {
    return _wsServer.connectedClients() > 0;
}

int AeroWebSocketServer::getClientCount() {
    return _wsServer.connectedClients();
}

void AeroWebSocketServer::_wsEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
    switch (type) {
        case WStype_DISCONNECTED:
            DEBUG_PRINTF("[WS] Client %u disconnected\n", num);
            if (_disconnectCallback) {
                _disconnectCallback(num);
            }
            break;

        case WStype_CONNECTED:
            DEBUG_PRINTF("[WS] Client %u connected from %s\n", num,
                _wsServer.remoteIP(num).toString().c_str());
            if (_connectCallback) {
                _connectCallback(num);
            }
            break;

        case WStype_TEXT:
            DEBUG_PRINTF("[WS] Client %u sent: %s\n", num, payload);
            if (_messageCallback) {
                _messageCallback((const char*)payload, length);
            }
            break;

        case WStype_BIN:
            DEBUG_PRINTF("[WS] Client %u sent binary data, length: %zu\n", num, length);
            break;

        case WStype_ERROR:
            DEBUG_PRINTF("[WS] Client %u error\n", num);
            break;

        case WStype_PING:
            DEBUG_PRINTF("[WS] Client %u ping\n", num);
            break;

        case WStype_PONG:
            DEBUG_PRINTF("[WS] Client %u pong\n", num);
            break;

        default:
            break;
    }
}
