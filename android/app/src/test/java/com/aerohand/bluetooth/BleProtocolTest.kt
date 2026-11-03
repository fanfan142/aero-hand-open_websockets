package com.aerohand.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

class BleProtocolTest {
    @Test
    fun acceptsFirmwareIdentityContract() {
        val identity = BleProtocol.parseIdentity(
            """{"firmware_type":"firmware_ws","firmware_version":"v0.2.0","hand_type":"Left","protocol_version":2,"capabilities":["CTRL_POS","GET_POS","HOMING"]}"""
                .toByteArray(StandardCharsets.UTF_8)
        )

        assertEquals(BleDeviceIdentity("Left", "v0.2.0", 2), identity)
    }

    @Test
    fun rejectsUnknownFirmwareProtocolAndMissingCapabilities() {
        val identity = BleProtocol.parseIdentity(
            """{"firmware_type":"other","firmware_version":"v0.2.0","hand_type":"Right","protocol_version":2,"capabilities":["CTRL_POS","GET_POS"]}"""
                .toByteArray(StandardCharsets.UTF_8)
        )

        assertNull(identity)
    }

    @Test
    fun acceptsOnlySixteenByteCommandFrames() {
        assertEquals(true, BleProtocol.isCommandFrame(ByteArray(16)))
        assertEquals(false, BleProtocol.isCommandFrame(ByteArray(15)))
        assertEquals(false, BleProtocol.isCommandFrame(ByteArray(17)))
    }
}
