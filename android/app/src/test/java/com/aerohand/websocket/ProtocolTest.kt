package com.aerohand.websocket

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun compactStateToActuations_matchesSevenActuatorReferenceMapping() {
        val state = compactStateOf(listOf(100f, 55f, 90f, 90f, 90f, 90f, 90f))

        val actuations = compactStateToActuations(state)

        assertEquals(7, actuations.size)
        assertEquals(30f, actuations[0], 0.001f)
        assertEquals(84.6801f, actuations[1], 0.001f)
        assertEquals(212.4276f, actuations[2], 0.001f)
        assertEquals(288.1230f, actuations[3], 0.001f)
        assertEquals(288.1230f, actuations[4], 0.001f)
        assertEquals(288.1230f, actuations[5], 0.001f)
        assertEquals(288.1230f, actuations[6], 0.001f)
    }

    @Test
    fun compactStateFromActuations_roundTripsWithinCompactRanges() {
        val state = compactStateOf(listOf(50f, 20f, 30f, 45f, 50f, 55f, 60f))

        val restored = compactStateFromActuations(compactStateToActuations(state))

        state.forEach { (key, expected) ->
            assertEquals(expected, restored.getValue(key), 0.01f)
        }
    }

    @Test
    fun statesResponseFromActuatorEquivalentState_roundTripsToCompactState() {
        val state = compactStateOf(listOf(50f, 20f, 30f, 45f, 50f, 55f, 60f))
        val restored = compactStateFromActuations(compactStateToActuations(state))
        val response = JSONObject().apply {
            put("type", "states_response")
            put("success", true)
            put("data", JSONObject().apply {
                put("joints", JSONArray().apply {
                    put(jointState("thumb_proximal", restored.getValue("thumb_cmc_flex")))
                    put(jointState("thumb_distal", restored.getValue("thumb_mcp_ip")))
                    listOf("index", "middle", "ring", "pinky").forEach { finger ->
                        val angle = restored.getValue("${finger}_flexion")
                        put(jointState("${finger}_proximal", angle))
                        put(jointState("${finger}_middle", angle))
                        put(jointState("${finger}_distal", angle))
                    }
                    put(
                        jointState(
                            "thumb_rotation",
                            mapRange(
                                restored.getValue("thumb_cmc_abd"),
                                0f,
                                100f,
                                ControlDefinitions.THUMB_ROTATION_MIN,
                                ControlDefinitions.THUMB_ROTATION_MAX
                            )
                        )
                    )
                })
            })
        }

        val parsed = parseStatesResponse(response.toString())
        val compact = compactStateFromJointStates(requireNotNull(parsed))

        state.forEach { (key, expected) ->
            assertEquals(expected, compact.getValue(key), 0.05f)
        }
    }

    @Test
    fun defaultWebSocketPayload_usesActuatorControlWithSevenTargets() {
        val state = compactStateOf(listOf(50f, 10f, 20f, 30f, 40f, 50f, 60f))

        val payload = JSONObject(buildActuatorControlPayload(state, durationMs = 40))

        assertEquals("actuator_control", payload.getString("type"))
        val data = payload.getJSONObject("data")
        assertEquals(40, data.getInt("duration_ms"))
        val actuators = data.getJSONArray("actuators")
        assertEquals(7, actuators.length())
        for (i in 0 until actuators.length()) {
            val actuator = actuators.getJSONObject(i)
            assertEquals(i, actuator.getInt("id"))
            assertTrue(actuator.has("angle"))
        }
    }

    @Test
    fun gestureFollowPayload_usesRealtimeActuatorControlWithinFirmwareLimits() {
        val gestureState = compactStateOf(listOf(100f, 55f, 90f, 90f, 90f, 90f, 90f))

        val payload = JSONObject(buildActuatorControlPayload(gestureState, durationMs = 24))

        assertEquals("actuator_control", payload.getString("type"))
        val data = payload.getJSONObject("data")
        assertEquals(24, data.getInt("duration_ms"))
        val actuators = data.getJSONArray("actuators")
        assertEquals(7, actuators.length())
        for (i in 0 until actuators.length()) {
            val actuator = actuators.getJSONObject(i)
            val id = actuator.getInt("id")
            val angle = actuator.getDouble("angle").toFloat()
            assertEquals(i, id)
            assertTrue(
                "actuator[$id]=$angle outside firmware limits",
                angle in ControlDefinitions.ACTUATION_LOWER_LIMITS[id]..ControlDefinitions.ACTUATION_UPPER_LIMITS[id]
            )
        }
    }

    @Test
    fun protocolPreview_defaultsToActuatorControl() {
        val preview = JSONObject(buildProtocolPreview(ControlDefinitions.DEFAULT_CONTROL_STATE))

        assertEquals("actuator_control", preview.getString("type"))
        assertEquals(7, preview.getJSONObject("data").getJSONArray("actuators").length())
    }

    @Test
    fun multiJointPayload_remainsAvailableForCompatibility() {
        val payload = JSONObject(buildMultiJointControlPayload(ControlDefinitions.DEFAULT_CONTROL_STATE))

        assertEquals("multi_joint_control", payload.getString("type"))
        assertEquals(15, payload.getJSONObject("data").getJSONArray("joints").length())
    }

    @Test
    fun firmwareInfoAndWifiStatusResponses_parseForUiFeedback() {
        val handInfo = """{"type":"hand_info","hand_type":"Right","firmware_version":"v0.2.0"}"""
        val wifiStatus = """
            {
              "type":"wifi_status",
              "data":{
                "mode":"STA",
                "ip":"192.168.1.210",
                "sta_ssid":"Lab_2G",
                "sta_static_ip":"192.168.1.210",
                "sta_gateway":"192.168.1.1",
                "sta_subnet":"255.255.255.0",
                "sta_dns1":"192.168.1.1",
                "sta_dns2":"114.114.114.114"
              }
            }
        """.trimIndent()

        assertEquals("Right", parseHandInfo(handInfo))
        val parsed = requireNotNull(parseWifiStatus(wifiStatus))
        assertEquals("STA", parsed.mode)
        assertEquals("192.168.1.210", parsed.ip)
        assertEquals("Lab_2G", parsed.staSsid)
        assertEquals("192.168.1.1", parsed.staGateway)
    }

    @Test
    fun deviceInfoDeclaresModernFirmwareCapabilities() {
        val modern = parseDeviceInfo(
            """{"type":"hand_info","hand_type":"Right","firmware_type":"firmware_ws","firmware_version":"v0.2.0","protocol_version":2}"""
        )
        val partialLegacy = parseDeviceInfo(
            """{"type":"hand_info","hand_type":"Right","firmware_type":"firmware_ws","firmware_version":"v0.1.5"}"""
        )
        val legacy = parseDeviceInfo("""{"type":"hand_info","hand_type":"Right"}""")

        assertNotNull(modern)
        assertEquals("Right", modern!!.handType)
        assertEquals("v0.2.0", modern.firmwareVersion)
        assertTrue(modern.capabilities.actuatorControl)
        assertTrue(modern.capabilities.wifiProvisioning)
        val partialLegacyInfo = requireNotNull(partialLegacy)
        assertTrue(partialLegacyInfo.capabilities.actuatorControl)
        assertFalse(partialLegacyInfo.capabilities.wifiProvisioning)
        assertNotNull(legacy)
        assertFalse(legacy!!.capabilities.actuatorControl)
        assertFalse(legacy.capabilities.wifiProvisioning)
    }

    @Test
    fun legacyV020WithoutProtocolVersionDoesNotEnableCorrelatedWifiProvisioning() {
        val legacyV020 = requireNotNull(
            parseDeviceInfo(
                """{"type":"hand_info","hand_type":"Right","firmware_type":"firmware_ws","firmware_version":"v0.2.0"}"""
            )
        )

        assertEquals("v0.2.0", legacyV020.firmwareVersion)
        assertEquals(0, legacyV020.protocolVersion)
        assertTrue(legacyV020.capabilities.actuatorControl)
        assertFalse(legacyV020.capabilities.wifiProvisioning)
    }

    @Test
    fun commandResponseParsesUnsupportedFirmwareErrorAndCorrelatedAck() {
        val unsupported = parseCommandResponse(
            """{"type":"response","success":false,"error":{"code":"COMMAND_ERROR","message":"Unknown command type"}}"""
        )
        val ack = parseCommandResponse(
            """{"type":"response","success":true,"request_id":"7-3","data":{"executed":true,"command_type":"wifi_connect_sta"}}"""
        )
        val genericAck = parseCommandResponse(
            """{"type":"response","success":true,"data":{"executed":true}}"""
        )

        assertEquals("Unknown command type", unsupported?.errorMessage)
        assertFalse(requireNotNull(unsupported).success)
        assertTrue(requireNotNull(ack).success)
        assertEquals("wifi_connect_sta", ack.commandType)
        assertEquals("7-3", ack.requestId)
        assertFalse(ack.isGenericExecutionAck)
        assertTrue(requireNotNull(genericAck).isGenericExecutionAck)
        assertNull(parseCommandResponse("""{"type":"wifi_status"}"""))
    }

    @Test
    fun wifiProvisioningPreservesCredentialsAndRequiresDeviceConfirmation() {
        val request = WifiProvisioningRequest(
            ssid = " Lab 2G ",
            password = " pass word ",
            staticIp = "192.168.31.210",
            gateway = "192.168.31.1",
            subnet = "255.255.255.0",
            dns1 = "192.168.31.1",
            dns2 = "1.1.1.1"
        )

        assertNull(validateWifiProvisioning(request))
        val payload = JSONObject(Commands.setWifiConfig(request, "4-9"))
        assertEquals(" Lab 2G ", payload.getJSONObject("data").getString("sta_ssid"))
        assertEquals(" pass word ", payload.getJSONObject("data").getString("sta_password"))
        assertEquals("4-9", payload.getString("request_id"))

        val confirmed = WifiStatus(
            staSsid = " Lab 2G ",
            staStaticIp = "192.168.31.210",
            requestId = "4-9",
            supportsStaticConfiguration = true
        )
        val stale = confirmed.copy(requestId = "4-8")
        assertTrue(isWifiProvisioningConfirmation(request, "4-9", confirmed))
        assertFalse(isWifiProvisioningConfirmation(request, "4-9", stale))
    }

    @Test
    fun wifiStatusDoesNotInventStaticConfigurationCapability() {
        val legacyStatus = parseWifiStatus(
            """{"type":"wifi_status","firmware_version":"v0.2.0","data":{"mode":"AP","ip":"192.168.4.1","sta_ssid":"Lab","sta_static_ip":"192.168.1.210","sta_gateway":"192.168.1.1","sta_subnet":"255.255.255.0","sta_dns1":"192.168.1.1","sta_dns2":"1.1.1.1"}}"""
        )
        val modernStatus = parseWifiStatus(
            """{"type":"wifi_status","protocol_version":2,"request_id":"2-1","data":{"mode":"AP","ip":"192.168.4.1","sta_ssid":"Lab","sta_static_ip":"192.168.1.210","sta_gateway":"192.168.1.1","sta_subnet":"255.255.255.0","sta_dns1":"192.168.1.1","sta_dns2":"1.1.1.1"}}"""
        )

        assertFalse(requireNotNull(legacyStatus).supportsStaticConfiguration)
        assertEquals(0, legacyStatus.protocolVersion)
        assertTrue(requireNotNull(modernStatus).supportsStaticConfiguration)
        assertEquals("2-1", modernStatus.requestId)
    }

    @Test
    fun wifiProvisioningRejectsInvalidOrCrossSubnetStaticNetwork() {
        val request = WifiProvisioningRequest(
            ssid = "Lab_2G",
            password = "12345678",
            staticIp = "192.168.31.210",
            gateway = "192.168.1.1",
            subnet = "255.255.255.0",
            dns1 = "192.168.1.1",
            dns2 = "1.1.1.1"
        )

        val error = validateWifiProvisioning(request)

        assertNotNull(error)
        assertTrue(error!!.contains("同一子网"))
    }

    @Test
    fun presetActions_haveValidUniqueStepsInsideCompactRanges() {
        val ids = PresetActions.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        val controlsById = ControlDefinitions.COMPACT_CONTROLS.associateBy { it.id }
        PresetActions.all.forEach { preset ->
            assertTrue("${preset.id} should have at least one step", preset.steps.isNotEmpty())
            preset.steps.forEachIndexed { index, step ->
                assertTrue("${preset.id}[$index] duration must be positive", step.durationMs > 0)
                assertEquals(
                    "${preset.id}[$index] should command every compact control",
                    controlsById.keys,
                    step.values.keys
                )
                step.values.forEach { (controlId, value) ->
                    val control = controlsById.getValue(controlId)
                    assertTrue(
                        "${preset.id}[$index].$controlId=$value outside ${control.min}..${control.max}",
                        value in control.min..control.max
                    )
                }
            }
        }
    }
}

private fun jointState(jointId: String, angle: Float): JSONObject {
    return JSONObject().apply {
        put("joint_id", jointId)
        put("angle", angle.toDouble())
    }
}
