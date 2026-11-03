package com.aerohand.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.aerohand.websocket.parseSerialActuationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import kotlin.coroutines.resume

sealed interface BleConnectionState {
    data object Disconnected : BleConnectionState
    data object Scanning : BleConnectionState
    data class Bonding(val deviceName: String) : BleConnectionState
    data class Connecting(val deviceName: String) : BleConnectionState
    data class Connected(val deviceName: String, val identity: BleDeviceIdentity) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

class BleTransportService(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState
    private val _devices = MutableStateFlow<List<BleScanDevice>>(emptyList())
    val devices: StateFlow<List<BleScanDevice>> = _devices
    private val _controlValues = MutableStateFlow<Map<String, Float>>(emptyMap())
    val controlValues: StateFlow<Map<String, Float>> = _controlValues

    private data class PendingGattOperation(
        val generation: Long,
        val description: String,
        val start: () -> Boolean,
        val complete: (Boolean, ByteArray?) -> Unit,
        val canStart: () -> Boolean,
        val position: Boolean = false
    )

    private val operationQueue = ArrayDeque<PendingGattOperation>()
    private var activeOperation: PendingGattOperation? = null
    private var operationTimeout: Runnable? = null
    private var generation = 0L
    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var gatt: BluetoothGatt? = null
    private var selectedDevice: BluetoothDevice? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    private var identityCharacteristic: BluetoothGattCharacteristic? = null
    private var receiverRegistered = false
    private var bondGeneration: Long? = null
    private var connectTimeout: Runnable? = null
    private var released = false

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            val token = bondGeneration ?: return
            if (token != generation || device.address != selectedDevice?.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> {
                    bondGeneration = null
                    connectGatt(token, device)
                }
                BluetoothDevice.BOND_NONE -> {
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (previous == BluetoothDevice.BOND_BONDING) {
                        bondGeneration = null
                        failGeneration(token, "Bluetooth 配对未完成，请重试")
                    }
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            // Bluetooth broadcasts can originate from the privileged Bluetooth process.
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    fun startScan() {
        val requestedGeneration = generation
        mainHandler.post {
            if (released || requestedGeneration != generation) return@post
            disconnectOnMain(setDisconnected = false)
            val adapter = bluetoothManager?.adapter
            if (adapter == null) {
                _connectionState.value = BleConnectionState.Error("此设备不支持 Bluetooth LE")
                return@post
            }
            if (!isBluetoothEnabled(adapter)) return@post
            val scanner = try {
                adapter.bluetoothLeScanner
            } catch (_: SecurityException) {
                null
            }
            if (scanner == null) {
                _connectionState.value = BleConnectionState.Error("无法访问蓝牙扫描器，请检查蓝牙权限")
                return@post
            }
            val token = ++generation
            _devices.value = emptyList()
            _connectionState.value = BleConnectionState.Scanning
            val found = linkedMapOf<String, BleScanDevice>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) = onMain {
                    if (token != generation || _connectionState.value != BleConnectionState.Scanning) return@onMain
                    try {
                        val device = result.device
                        val name = device.name ?: result.scanRecord?.deviceName ?: "Aero Hand"
                        found[device.address] = BleScanDevice(device.address, name, result.rssi)
                        _devices.value = found.values.sortedByDescending { it.rssi }
                    } catch (_: SecurityException) {
                        failGeneration(token, "蓝牙权限已撤销，请重新授权")
                    }
                }

                override fun onScanFailed(errorCode: Int) = onMain {
                    if (token == generation) {
                        stopScanOnMain()
                        _connectionState.value = BleConnectionState.Error("蓝牙扫描失败（$errorCode）")
                    }
                }
            }
            scanCallback = callback
            try {
                scanner.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(BleProtocol.SERVICE_UUID)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback
                )
                scanTimeout = Runnable {
                    if (token == generation && _connectionState.value == BleConnectionState.Scanning) {
                        stopScanOnMain()
                        _connectionState.value = BleConnectionState.Disconnected
                    }
                }.also { mainHandler.postDelayed(it, SCAN_TIMEOUT_MS) }
            } catch (_: SecurityException) {
                stopScanOnMain()
                _connectionState.value = BleConnectionState.Error("蓝牙扫描权限不足，请授权附近设备")
            } catch (exception: RuntimeException) {
                stopScanOnMain()
                _connectionState.value = BleConnectionState.Error("无法启动蓝牙扫描：${exception.message ?: "未知错误"}")
            }
        }
    }

    fun stopScan() {
        onMain {
            stopScanOnMain()
            if (_connectionState.value == BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    private fun stopScanOnMain() {
        scanTimeout?.let(mainHandler::removeCallbacks)
        scanTimeout = null
        val callback = scanCallback
        scanCallback = null
        if (callback != null) {
            try {
                bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: SecurityException) {
                // The OS may revoke scan permission while a scan is active.
            } catch (_: RuntimeException) {
                // The scanner may already be shut down by the platform.
            }
        }
    }

    fun connect(address: String) {
        val requestedGeneration = generation
        mainHandler.post {
            if (released || requestedGeneration != generation) return@post
            stopScanOnMain()
            val adapter = bluetoothManager?.adapter
            val device = try {
                adapter?.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (adapter == null || device == null) {
                _connectionState.value = BleConnectionState.Error("所选蓝牙设备不可用，请重新扫描")
                return@post
            }
            if (!isBluetoothEnabled(adapter)) return@post
            disconnectOnMain(setDisconnected = false)
            val token = ++generation
            selectedDevice = device
            val name = try {
                device.name ?: _devices.value.firstOrNull { it.address == address }?.name ?: "Aero Hand"
            } catch (_: SecurityException) {
                _devices.value.firstOrNull { it.address == address }?.name ?: "Aero Hand"
            }
            connectTimeout = Runnable {
                if (token == generation && _connectionState.value !is BleConnectionState.Connected) {
                    failGeneration(token, "蓝牙连接超时，请重试")
                }
            }.also { mainHandler.postDelayed(it, CONNECT_TIMEOUT_MS) }
            try {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> connectGatt(token, device)
                    BluetoothDevice.BOND_BONDING -> {
                        bondGeneration = token
                        _connectionState.value = BleConnectionState.Bonding(name)
                    }
                    else -> {
                        bondGeneration = token
                        _connectionState.value = BleConnectionState.Bonding(name)
                        val started = device.createBond()
                        if (!started && device.bondState != BluetoothDevice.BOND_BONDING) {
                            bondGeneration = null
                            failGeneration(token, "无法启动 Bluetooth 配对，请检查设备状态")
                        }
                    }
                }
            } catch (_: SecurityException) {
                failGeneration(token, "蓝牙连接权限不足，请授权附近设备")
            } catch (exception: RuntimeException) {
                failGeneration(token, "无法启动 Bluetooth 配对：${exception.message ?: "未知错误"}")
            }
        }
    }

    private fun connectGatt(token: Long, device: BluetoothDevice) {
        if (token != generation || released) return
        try {
            val name = try {
                device.name ?: "Aero Hand"
            } catch (_: SecurityException) {
                "Aero Hand"
            }
            bondGeneration = null
            _connectionState.value = BleConnectionState.Connecting(name)
            gatt = device.connectGatt(context, false, createGattCallback(token), BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) failGeneration(token, "Bluetooth GATT 连接未启动")
        } catch (_: SecurityException) {
            failGeneration(token, "蓝牙连接权限不足，请授权附近设备")
        } catch (exception: RuntimeException) {
            failGeneration(token, "无法连接蓝牙设备：${exception.message ?: "未知错误"}")
        }
    }

    private fun createGattCallback(token: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            onMain {
                if (!isCurrent(token, callbackGatt)) return@onMain
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    failGeneration(token, if (status == BluetoothGatt.GATT_SUCCESS) "Bluetooth 已断开" else "Bluetooth GATT 错误（$status）")
                    return@onMain
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    scope.launch {
                        val discovered = performOperation(token, "发现 BLE 服务") {
                            callbackGatt.discoverServices()
                        }
                        if (!discovered.success) {
                            failGeneration(token, "无法发现 Aero Hand BLE 服务")
                            return@launch
                        }
                        initializeGatt(token, callbackGatt)
                    }
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) = onMain {
            if (isCurrent(token, callbackGatt)) completeOperation(token, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) = onMain {
            if (isCurrent(token, callbackGatt)) completeOperation(token, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val value = characteristic.value?.copyOf()
            onMain {
                if (isCurrent(token, callbackGatt)) {
                    completeOperation(token, status == BluetoothGatt.GATT_SUCCESS, value)
                }
            }
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) = onMain {
            if (isCurrent(token, callbackGatt)) {
                completeOperation(token, status == BluetoothGatt.GATT_SUCCESS, value.copyOf())
            }
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) = onMain {
            if (isCurrent(token, callbackGatt)) completeOperation(token, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value?.copyOf()
            onMain {
                if (isCurrent(token, callbackGatt) && characteristic.uuid == BleProtocol.RESPONSE_UUID) {
                    acceptResponse(value)
                }
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = onMain {
            if (isCurrent(token, callbackGatt) && characteristic.uuid == BleProtocol.RESPONSE_UUID) {
                acceptResponse(value.copyOf())
            }
        }
    }

    private suspend fun initializeGatt(token: Long, callbackGatt: BluetoothGatt) {
        val service = callbackGatt.getService(BleProtocol.SERVICE_UUID)
        val command = service?.getCharacteristic(BleProtocol.COMMAND_UUID)
        val response = service?.getCharacteristic(BleProtocol.RESPONSE_UUID)
        val identity = service?.getCharacteristic(BleProtocol.IDENTITY_UUID)
        if (service == null || command == null || response == null || identity == null ||
            command.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
            response.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 ||
            identity.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            failGeneration(token, "BLE 服务或特征值与 Aero Hand 协议不匹配")
            return
        }
        commandCharacteristic = command
        responseCharacteristic = response
        identityCharacteristic = identity
        val cccd = response.getDescriptor(BleProtocol.CCCD_UUID)
        val notificationsEnabled = try {
            cccd != null && callbackGatt.setCharacteristicNotification(response, true)
        } catch (_: SecurityException) {
            false
        }
        if (cccd == null || !notificationsEnabled) {
            failGeneration(token, "无法启用 BLE 响应通知")
            return
        }
        val subscription = performOperation(token, "订阅 BLE 响应") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                callbackGatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                callbackGatt.writeDescriptor(cccd)
            }
        }
        if (!subscription.success) {
            failGeneration(token, "BLE 通知订阅失败")
            return
        }
        val identityResult = performOperation(token, "读取 BLE 设备身份") {
            @Suppress("DEPRECATION")
            callbackGatt.readCharacteristic(identity)
        }
        if (!identityResult.success) {
            failGeneration(token, "读取 BLE 设备身份失败")
            return
        }
        val parsedIdentity = identityResult.value?.let(BleProtocol::parseIdentity)
        if (parsedIdentity == null) {
            failGeneration(token, "BLE 设备身份无效或固件协议不兼容")
            return
        }
        // The identity and CCCD are public on this firmware. A successful write to
        // WRITE_ENC proves the bonded link has actually enabled encryption, including
        // reconnects with an existing bond. GET_POS does not actuate the hand.
        val secureQuery = com.aerohand.websocket.buildSerialGetPositionsFrame()
        val secure = performOperation(token, "验证 BLE 加密链路") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                callbackGatt.writeCharacteristic(command, secureQuery, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                command.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                command.value = secureQuery
                @Suppress("DEPRECATION")
                callbackGatt.writeCharacteristic(command)
            }
        }
        if (!secure.success) return
        if (isCurrent(token, callbackGatt)) {
            val deviceName = when (val state = _connectionState.value) {
                is BleConnectionState.Connecting -> state.deviceName
                else -> "Aero Hand"
            }
            connectTimeout?.let(mainHandler::removeCallbacks)
            connectTimeout = null
            _connectionState.value = BleConnectionState.Connected(deviceName, parsedIdentity)
        }
    }

    private data class GattOperationResult(val success: Boolean, val value: ByteArray? = null)

    private suspend fun performOperation(
        token: Long,
        description: String,
        position: Boolean = false,
        isFresh: () -> Boolean = { true },
        start: () -> Boolean
    ): GattOperationResult = suspendCancellableCoroutine { continuation ->
        mainHandler.post {
            if (!continuation.isActive) return@post
            if (!isCurrent(token, gatt)) {
                continuation.resume(GattOperationResult(false))
                return@post
            }
            if (position) {
                val superseded = operationQueue.filter { it.position }
                operationQueue.removeAll(superseded.toSet())
                superseded.forEach { it.complete(false, null) }
            }
            val operation = PendingGattOperation(
                token, description, start,
                complete = { success, value ->
                    if (continuation.isActive) continuation.resume(GattOperationResult(success, value))
                },
                canStart = { continuation.isActive && isFresh() },
                position = position
            )
            operationQueue.addLast(operation)
            continuation.invokeOnCancellation {
                onMain {
                    operationQueue.remove(operation)
                    // An ATT write already handed to Android cannot be unsent. Closing
                    // the generation prevents further writes or a late completion reuse.
                    if (activeOperation === operation && token == generation) {
                        failGeneration(token, "BLE 指令已取消，请重新连接")
                    }
                }
            }
            startNextOperation()
        }
    }

    private fun startNextOperation() {
        if (activeOperation != null) return
        while (operationQueue.isNotEmpty()) {
            val next = operationQueue.removeFirst()
            if (next.generation != generation || gatt == null || !next.canStart()) {
                next.complete(false, null)
                continue
            }
            activeOperation = next
            val accepted = try {
                next.start()
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                false
            }
            if (!accepted) {
                failGeneration(next.generation, "${next.description}失败，请检查蓝牙权限或重新配对")
                return
            }
            val token = next.generation
            operationTimeout = Runnable {
                if (activeOperation === next && token == generation) {
                    failGeneration(token, "${next.description}超时")
                }
            }.also { mainHandler.postDelayed(it, OPERATION_TIMEOUT_MS) }
            return
        }
    }

    private fun completeOperation(token: Long, success: Boolean, value: ByteArray? = null) {
        if (token != generation) return
        val active = activeOperation ?: return
        if (active.generation != token) return
        if (!success) {
            failGeneration(token, "${active.description}失败，请重新连接或在系统中重新配对")
            return
        }
        operationTimeout?.let(mainHandler::removeCallbacks)
        operationTimeout = null
        activeOperation = null
        active.complete(success, value)
        startNextOperation()
    }

    private fun acceptResponse(value: ByteArray?) {
        if (value == null || value.size != 16) return
        val positions = parseSerialActuationResponse(value) ?: return
        _controlValues.value = positions
    }

    suspend fun sendPosition(frame: ByteArray): Boolean =
        sendFrame(frame.copyOf(), generation, position = true)

    suspend fun sendCommand(frame: ByteArray): Boolean =
        sendFrame(frame.copyOf(), generation)

    private suspend fun sendFrame(frame: ByteArray, token: Long, position: Boolean = false): Boolean {
        if (!BleProtocol.isCommandFrame(frame) || token != generation ||
            _connectionState.value !is BleConnectionState.Connected) return false
        val characteristic = commandCharacteristic ?: return false
        val queuedAt = SystemClock.elapsedRealtime()
        val result = performOperation(
            token, "发送 BLE 指令", position,
            isFresh = { !position || SystemClock.elapsedRealtime() - queuedAt < 240L }
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(
                    characteristic,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(characteristic) == true
            }
        }
        return result.success && token == generation
    }

    fun disconnect() {
        onMain { disconnectOnMain(setDisconnected = true) }
    }

    private fun disconnectOnMain(setDisconnected: Boolean) {
        ++generation
        stopScanOnMain()
        connectTimeout?.let(mainHandler::removeCallbacks)
        connectTimeout = null
        bondGeneration = null
        operationTimeout?.let(mainHandler::removeCallbacks)
        operationTimeout = null
        activeOperation?.complete(false, null)
        activeOperation = null
        while (operationQueue.isNotEmpty()) operationQueue.removeFirst().complete(false, null)
        val oldGatt = gatt
        gatt = null
        commandCharacteristic = null
        responseCharacteristic = null
        identityCharacteristic = null
        selectedDevice = null
        oldGatt?.let {
            try {
                it.disconnect()
            } catch (_: SecurityException) {
                // Still close the local GATT client below.
            } finally {
                it.close()
            }
        }
        _controlValues.value = emptyMap()
        if (setDisconnected) _connectionState.value = BleConnectionState.Disconnected
    }

    private fun failGeneration(token: Long, message: String) {
        if (token != generation) return
        disconnectOnMain(setDisconnected = false)
        _connectionState.value = BleConnectionState.Error(message)
    }

    private fun isBluetoothEnabled(adapter: BluetoothAdapter): Boolean {
        return try {
            adapter.isEnabled.also { enabled ->
                if (!enabled) _connectionState.value = BleConnectionState.Error("请先打开系统蓝牙")
            }
        } catch (_: SecurityException) {
            _connectionState.value = BleConnectionState.Error("蓝牙连接权限不足，请授权附近设备")
            false
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    private fun isCurrent(token: Long, callbackGatt: BluetoothGatt?): Boolean =
        token == generation && callbackGatt != null && callbackGatt === gatt

    fun release() {
        onMain {
            if (released) return@onMain
            released = true
            disconnectOnMain(setDisconnected = true)
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(bondReceiver)
                } catch (_: IllegalArgumentException) {
                    // The receiver may already have been unregistered by the platform.
                }
                receiverRegistered = false
            }
            scope.cancel()
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 12_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val OPERATION_TIMEOUT_MS = 8_000L
    }
}
