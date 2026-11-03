package com.aerohand.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.os.Looper
import com.aerohand.websocket.buildSerialGetPositionsFrame
import com.aerohand.websocket.buildSerialPositionControlFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class BleTransportServiceTest {
    private val service = BleTransportService(RuntimeEnvironment.getApplication())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gatt = mock(BluetoothGatt::class.java)
    private val command = mock(BluetoothGattCharacteristic::class.java)
    private val mainLooper = shadowOf(Looper.getMainLooper())

    @After
    fun close() {
        service.release()
        mainLooper.idle()
        scope.cancel()
    }

    @Test
    fun disconnectedSendsReturnFalseWithoutTouchingGatt() {
        setField("gatt", gatt)
        setField("commandCharacteristic", command)

        val position = sendPosition()
        val query = sendQuery()
        mainLooper.idle()

        assertFalse(result(position))
        assertFalse(result(query))
        assertEquals(BleConnectionState.Disconnected, service.connectionState.value)
        verifyNoInteractions(gatt)
    }

    @Test
    fun disconnectResolvesActiveAndQueuedWritesAndIgnoresOldCallbacks() {
        connectedSession()
        val callback = callbackForCurrentGeneration()
        controlValues().value = mapOf("index_flexion" to 42f)
        val active = sendPosition()
        val queued = sendQuery()
        mainLooper.idle()
        assertFalse(active.isCompleted)
        assertFalse(queued.isCompleted)
        verify(gatt, times(1)).writeCharacteristic(command)

        service.disconnect()
        mainLooper.idle()

        assertFalse(result(active))
        assertFalse(result(queued))
        assertEquals(BleConnectionState.Disconnected, service.connectionState.value)
        assertTrue(service.controlValues.value.isEmpty())
        assertNull(field("gatt"))
        assertNull(field("activeOperation"))
        assertNull(field("commandCharacteristic"))
        assertTrue((field("operationQueue") as java.util.ArrayDeque<*>).isEmpty())
        verify(gatt).disconnect()
        verify(gatt).close()

        // Reuse the same GATT object to prove generation, rather than object identity,
        // rejects an old completion and prevents it from advancing the new queue.
        connectedSession()
        val replacement = sendQuery()
        mainLooper.idle()
        callback.onCharacteristicWrite(gatt, command, BluetoothGatt.GATT_SUCCESS)
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
        mainLooper.idle()

        assertFalse(replacement.isCompleted)
        assertTrue(service.connectionState.value is BleConnectionState.Connected)
        verify(gatt, times(2)).writeCharacteristic(command)
        service.disconnect()
        mainLooper.idle()
        assertFalse(result(replacement))
    }

    @Test
    fun operationTimeoutClosesGenerationAndLateSuccessCannotRestoreConnection() {
        connectedSession()
        val callback = callbackForCurrentGeneration()
        val active = sendQuery()
        val queued = sendQuery()
        mainLooper.idle()
        verify(gatt, times(1)).writeCharacteristic(command)

        mainLooper.idleFor(Duration.ofSeconds(8))

        assertFalse(result(active))
        assertFalse(result(queued))
        val error = service.connectionState.value
        assertTrue(error is BleConnectionState.Error)
        assertTrue((error as BleConnectionState.Error).message.contains("超时"))
        assertNull(field("gatt"))
        assertNull(field("activeOperation"))
        verify(gatt).disconnect()
        verify(gatt).close()

        callback.onCharacteristicWrite(gatt, command, BluetoothGatt.GATT_SUCCESS)
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        mainLooper.idle()

        assertEquals(error, service.connectionState.value)
        verify(gatt, times(1)).writeCharacteristic(command)
    }

    private fun connectedSession() {
        setField("gatt", gatt)
        setField("commandCharacteristic", command)
        connectionState().value = BleConnectionState.Connected(
            "Aero Hand", BleDeviceIdentity("Left", "v0.2.0", 2)
        )
        `when`(gatt.writeCharacteristic(command)).thenReturn(true)
    }

    private fun sendPosition(): Deferred<Boolean> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        service.sendPosition(buildSerialPositionControlFrame(emptyMap()))
    }

    private fun sendQuery(): Deferred<Boolean> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        service.sendCommand(buildSerialGetPositionsFrame())
    }

    private fun result(write: Deferred<Boolean>): Boolean {
        assertTrue("Write must resolve without waiting for hardware", write.isCompleted)
        return runBlocking { write.await() }
    }

    private fun callbackForCurrentGeneration(): BluetoothGattCallback {
        val method = BleTransportService::class.java.getDeclaredMethod(
            "createGattCallback", java.lang.Long.TYPE
        ).apply { isAccessible = true }
        return method.invoke(service, field("generation") as Long) as BluetoothGattCallback
    }

    @Suppress("UNCHECKED_CAST")
    private fun connectionState() = field("_connectionState") as MutableStateFlow<BleConnectionState>

    @Suppress("UNCHECKED_CAST")
    private fun controlValues() = field("_controlValues") as MutableStateFlow<Map<String, Float>>

    private fun field(name: String): Any? = BleTransportService::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(name: String, value: Any?) {
        BleTransportService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(service, value)
    }
}
