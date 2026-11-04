package com.aerohand.gesture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GestureCalibrationStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("gesture_follow_v2", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @Test
    fun rollingCaptureProfileRequiresFreshGuidedCalibration() {
        val store = GestureCalibrationStore(context)
        val profile = GestureCalibrationProfile(handSide = "Right")
        store.saveProfile(profile)
        prefs.edit().putInt("${profile.key}.schemaVersion", 6).commit()

        assertTrue(store.loadProfiles().isEmpty())
        assertTrue(store.needsRecalibration())
    }

    @Test
    fun newGuidedProfileRoundTripsAndKeepsTuning() {
        val store = GestureCalibrationStore(context)
        val tuning = GestureTuningProfile.default().withAdjustment(GestureTuningChannel.INDEX_TENDON, 0.1f, 2f)
        store.saveTuning(tuning)
        val profile = GestureCalibrationProfile(handSide = "Right")
        store.saveProfile(profile)

        assertEquals(GestureCalibrationStore.SCHEMA_VERSION, store.loadProfiles()[profile.key]?.schemaVersion)
        assertFalse(store.needsRecalibration())
        assertEquals(tuning.gainAt(GestureTuningChannel.INDEX_TENDON), store.loadTuning().gainAt(GestureTuningChannel.INDEX_TENDON), 0.001f)
    }
}
