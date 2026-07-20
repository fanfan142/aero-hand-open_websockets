package com.aerohand.websocket

import org.json.JSONObject

internal const val CORRELATED_WIFI_PROTOCOL_VERSION = 2

data class DeviceCapabilities(
    val identified: Boolean = false,
    val actuatorControl: Boolean = false,
    val wifiProvisioning: Boolean = false
)

data class DeviceInfo(
    val handType: String,
    val firmwareType: String = "",
    val firmwareVersion: String = "",
    val protocolVersion: Int = 0,
    val capabilities: DeviceCapabilities = capabilitiesFor(firmwareType, protocolVersion)
)

data class CommandResponse(
    val success: Boolean,
    val commandType: String? = null,
    val requestId: String? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val isGenericExecutionAck: Boolean = false
)

data class WifiProvisioningRequest(
    val ssid: String,
    val password: String,
    val staticIp: String,
    val gateway: String,
    val subnet: String,
    val dns1: String,
    val dns2: String
)

sealed class WifiProvisioningResult {
    data class SwitchScheduled(val targetIp: String) : WifiProvisioningResult()
    data class Failure(val message: String) : WifiProvisioningResult()
}

sealed class DeviceCommandResult {
    object Success : DeviceCommandResult()
    data class Failure(val message: String) : DeviceCommandResult()
}

fun parseDeviceInfo(text: String): DeviceInfo? {
    return try {
        val json = JSONObject(text)
        if (json.optString("type") != "hand_info") return null
        DeviceInfo(
            handType = json.optString("hand_type"),
            firmwareType = json.optString("firmware_type"),
            firmwareVersion = json.optString("firmware_version"),
            protocolVersion = json.optInt("protocol_version", 0)
        )
    } catch (_: Exception) {
        null
    }
}

fun parseCommandResponse(text: String): CommandResponse? {
    return try {
        val json = JSONObject(text)
        if (json.optString("type") != "response") return null
        val success = json.optBoolean("success", false)
        val data = json.optJSONObject("data")
        val error = json.optJSONObject("error")
        val commandType = data?.optString("command_type")?.takeIf { it.isNotBlank() }
            ?: json.optString("command_type").takeIf { it.isNotBlank() }
        val requestId = data?.optString("request_id")?.takeIf { it.isNotBlank() }
            ?: json.optString("request_id").takeIf { it.isNotBlank() }
        val message = data?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
        val errorMessage = error?.optString("message")?.takeIf { it.isNotBlank() }
        CommandResponse(
            success = success,
            commandType = commandType,
            requestId = requestId,
            message = message,
            errorMessage = errorMessage,
            isGenericExecutionAck = success && commandType == null && message == null && data?.optBoolean("executed") == true
        )
    } catch (_: Exception) {
        null
    }
}

fun validateWifiProvisioning(request: WifiProvisioningRequest): String? {
    val ssidBytes = request.ssid.toByteArray(Charsets.UTF_8).size
    if (request.ssid.isBlank()) return "请输入 STA WiFi 名称"
    if (ssidBytes > 32) return "STA WiFi 名称不能超过 32 字节"

    val passwordBytes = request.password.toByteArray(Charsets.UTF_8).size
    val isHexPsk = passwordBytes == 64 && request.password.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    if (passwordBytes != 0 && passwordBytes !in 8..63 && !isHexPsk) {
        return "STA 密码应为空（开放网络）、8-63 字节或 64 位十六进制 PSK"
    }

    val ip = parseIpv4(request.staticIp) ?: return "静态 IP 格式不合法"
    val gateway = parseIpv4(request.gateway) ?: return "网关格式不合法"
    val subnet = parseSubnetMask(request.subnet) ?: return "子网掩码格式不合法"
    if (parseIpv4(request.dns1) == null) return "DNS1 格式不合法"
    if (parseIpv4(request.dns2) == null) return "DNS2 格式不合法"
    if ((ip and subnet) != (gateway and subnet)) {
        return "静态 IP 与网关必须位于同一子网"
    }
    return null
}

fun isWifiProvisioningConfirmation(
    request: WifiProvisioningRequest,
    requestId: String,
    status: WifiStatus
): Boolean {
    return status.supportsStaticConfiguration &&
        status.requestId == requestId &&
        status.staSsid == request.ssid &&
        status.staStaticIp == request.staticIp
}

private fun capabilitiesFor(firmwareType: String, protocolVersion: Int): DeviceCapabilities {
    val isSupportedFirmware = firmwareType == "esp32_wifi" || firmwareType == "firmware_ws"
    if (!isSupportedFirmware) {
        return DeviceCapabilities(identified = true)
    }

    // firmware_version intentionally does not participate: historical v0.2.0 images
    // do not implement request_id/command_type-correlated provisioning acknowledgements.
    return DeviceCapabilities(
        identified = true,
        actuatorControl = true,
        wifiProvisioning = protocolVersion >= CORRELATED_WIFI_PROTOCOL_VERSION
    )
}

private fun parseIpv4(value: String): Long? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    var result = 0L
    for (part in parts) {
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        result = (result shl 8) or octet.toLong()
    }
    return result
}

private fun parseSubnetMask(value: String): Long? {
    val mask = parseIpv4(value) ?: return null
    if (mask == 0L) return null
    val inverted = mask.inv() and 0xFFFF_FFFFL
    return mask.takeIf { (inverted and (inverted + 1L)) == 0L }
}
