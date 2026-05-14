package com.aerohand.websocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
