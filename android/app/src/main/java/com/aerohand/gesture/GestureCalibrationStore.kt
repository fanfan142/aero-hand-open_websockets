package com.aerohand.gesture

import android.content.Context
import android.content.SharedPreferences

class GestureCalibrationStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("gesture_follow_v2", Context.MODE_PRIVATE)

    fun loadProfiles(): Map<String, GestureCalibrationProfile> {
        return profileKeys().mapNotNull { key ->
            loadProfile(key)?.let { profile -> key to profile }
        }.toMap()
    }

    fun saveProfile(profile: GestureCalibrationProfile) {
        val key = profile.key
        prefs.edit().apply {
            putStringSet("profileKeys", profileKeys() + key)
            putInt("$key.schemaVersion", SCHEMA_VERSION)
            putString("$key.handSide", profile.handSide)
            putString("$key.cameraFacing", profile.cameraFacing.name)
            putString("$key.mirrorMode", profile.mirrorMode.name)
            putString("$key.openAngles", profile.openAngles.joinToString(","))
            putString("$key.fistAngles", profile.fistAngles.joinToString(","))
            putFloat("$key.openThumbSwing", profile.openThumbSwing)
            putFloat("$key.thumbInSwing", profile.thumbInSwing)
            putString("activeProfileKey", key)
            apply()
        }
    }

    fun activeProfileKey(): String? = prefs.getString("activeProfileKey", null)

    fun loadTuning(): GestureTuningProfile {
        val gains = parseFloatArray(
            prefs.getString("tuning.gains", null),
            GestureTuningChannel.entries.size,
            1f
        )
        val offsets = parseFloatArray(
            prefs.getString("tuning.offsets", null),
            GestureTuningChannel.entries.size,
            0f
        )
        return GestureTuningProfile(gains = gains, offsets = offsets)
    }

    fun saveTuning(tuning: GestureTuningProfile) {
        prefs.edit().apply {
            putInt("tuning.schemaVersion", TUNING_SCHEMA_VERSION)
            putString("tuning.gains", tuning.gains.joinToString(","))
            putString("tuning.offsets", tuning.offsets.joinToString(","))
            apply()
        }
    }

    private fun profileKeys(): Set<String> = prefs.getStringSet("profileKeys", emptySet()).orEmpty()

    private fun loadProfile(key: String): GestureCalibrationProfile? {
        if (prefs.getInt("$key.schemaVersion", 0) < SCHEMA_VERSION) return null
        val hand = prefs.getString("$key.handSide", null).orEmpty()
        if (hand.isBlank()) return null
        val facing = runCatching {
            GestureCameraFacing.valueOf(prefs.getString("$key.cameraFacing", GestureCameraFacing.FRONT.name)!!)
        }.getOrDefault(GestureCameraFacing.FRONT)
        val mirror = runCatching {
            GestureMirrorMode.valueOf(prefs.getString("$key.mirrorMode", GestureMirrorMode.SELFIE.name)!!)
        }.getOrDefault(GestureMirrorMode.SELFIE)
        val open = parseFloatArray(
            prefs.getString("$key.openAngles", null),
            GestureTuningChannel.entries.size,
            0f
        )
        val fist = parseFloatArray(
            prefs.getString("$key.fistAngles", null),
            GestureTuningChannel.entries.size,
            0f
        )
        return GestureCalibrationProfile(
            schemaVersion = SCHEMA_VERSION,
            handSide = hand,
            cameraFacing = facing,
            mirrorMode = mirror,
            openAngles = open,
            fistAngles = fist,
            openThumbSwing = prefs.getFloat("$key.openThumbSwing", 0f),
            thumbInSwing = prefs.getFloat("$key.thumbInSwing", 0f)
        )
    }

    private fun parseFloatArray(raw: String?, size: Int, fallback: Float): FloatArray {
        val parsed = raw
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            .orEmpty()
        return FloatArray(size) { index -> parsed.getOrNull(index) ?: fallback }
    }

    companion object {
        const val SCHEMA_VERSION = 5
        private const val TUNING_SCHEMA_VERSION = 1
    }
}
