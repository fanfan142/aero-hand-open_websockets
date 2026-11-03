package com.aerohand.bluetooth

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

internal object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("8c6f4a20-6c0b-4b7a-9d27-6df8a3210001")
    val COMMAND_UUID: UUID = UUID.fromString("8c6f4a20-6c0b-4b7a-9d27-6df8a3210002")
    val RESPONSE_UUID: UUID = UUID.fromString("8c6f4a20-6c0b-4b7a-9d27-6df8a3210003")
    val IDENTITY_UUID: UUID = UUID.fromString("8c6f4a20-6c0b-4b7a-9d27-6df8a3210004")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun isCommandFrame(frame: ByteArray): Boolean = frame.size == 16

    fun parseIdentity(payload: ByteArray): BleDeviceIdentity? {
        return try {
            val json = JSONObject(String(payload, StandardCharsets.UTF_8))
            val capabilitiesJson = json.optJSONArray("capabilities") ?: return null
            val capabilities = (0 until capabilitiesJson.length())
                .mapNotNull { capabilitiesJson.optString(it).takeIf(String::isNotBlank) }
                .toSet()
            val firmwareType = json.optString("firmware_type")
            val firmwareVersion = json.optString("firmware_version")
            val protocolVersion = json.optInt("protocol_version", -1)
            val handType = json.optString("hand_type")
            if (firmwareType != "firmware_ws" || protocolVersion != 2 ||
                firmwareVersion.isBlank() || handType !in setOf("Left", "Right", "Unknown") ||
                !capabilities.containsAll(setOf("CTRL_POS", "GET_POS", "HOMING"))) {
                null
            } else {
                BleDeviceIdentity(handType, firmwareVersion, protocolVersion)
            }
        } catch (_: Exception) {
            null
        }
    }
}

data class BleDeviceIdentity(
    val handType: String,
    val firmwareVersion: String,
    val protocolVersion: Int
)

data class BleScanDevice(
    val address: String,
    val name: String,
    val rssi: Int
)
