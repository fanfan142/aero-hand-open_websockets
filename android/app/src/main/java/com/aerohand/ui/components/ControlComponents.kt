package com.aerohand.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerohand.viewmodel.ConnectionMode
import com.aerohand.viewmodel.WifiConfigUiState
import com.aerohand.bluetooth.BleScanDevice
import com.aerohand.wifi.DiscoveredHand

// ============== 连接面板 ==============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionPanel(
    mode: ConnectionMode,
    host: String,
    port: String,
    wifiConnected: Boolean,
    usbConnected: Boolean,
    bleConnected: Boolean,
    bleDevices: List<BleScanDevice>,
    bleSelectedAddress: String?,
    bleScanning: Boolean,
    bleConnecting: Boolean,
    blePermissionRequired: Boolean,
    statusMessage: String,
    wifiConfig: WifiConfigUiState,
    discoveredHands: List<DiscoveredHand>,
    discoveryStatus: String,
    onDiscoveredHandClick: (String) -> Unit,
    firmwareIdentified: Boolean,
    wifiProvisioningSupported: Boolean,
    firmwareVersion: String?,
    onModeChange: (ConnectionMode) -> Unit,
    onRequestBlePermission: () -> Unit,
    onScanBle: () -> Unit,
    onSelectBleDevice: (String) -> Unit,
    onConnectSelectedBleDevice: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStaSsidChange: (String) -> Unit,
    onStaPasswordChange: (String) -> Unit,
    onStaStaticIpChange: (String) -> Unit,
    onStaGatewayChange: (String) -> Unit,
    onStaSubnetChange: (String) -> Unit,
    onStaDns1Change: (String) -> Unit,
    onStaDns2Change: (String) -> Unit,
    onScanWifi: () -> Unit,
    onApplyStaConfig: () -> Unit,
    onSwitchToAp: () -> Unit,
    onClearStaConfig: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier
) {
    val connected = when (mode) {
        ConnectionMode.WIFI -> wifiConnected
        ConnectionMode.USB -> usbConnected
        ConnectionMode.BLE -> bleConnected
    }
    val gradient = Brush.horizontalGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
    )

    val selectedTab = when (mode) {
        ConnectionMode.WIFI -> 0
        ConnectionMode.USB -> 1
        ConnectionMode.BLE -> 2
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .background(gradient)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("连接控制台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusBadge(if (connected) "ONLINE" else "OFFLINE", connected)
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(14.dp))

                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicator = {},
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { onModeChange(ConnectionMode.WIFI) },
                        text = { Text("WiFi") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { onModeChange(ConnectionMode.USB) },
                        text = { Text("USB OTG") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { onModeChange(ConnectionMode.BLE) },
                        text = { Text("Bluetooth") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    when (selectedTab) {
                        0 -> WifiConnectionContent(
                            host = host,
                            port = port,
                            connected = wifiConnected,
                            wifiConfig = wifiConfig,
                            discoveredHands = discoveredHands,
                            discoveryStatus = discoveryStatus,
                            onDiscoveredHandClick = onDiscoveredHandClick,
                            firmwareIdentified = firmwareIdentified,
                            wifiProvisioningSupported = wifiProvisioningSupported,
                            firmwareVersion = firmwareVersion,
                            onHostChange = onHostChange,
                            onPortChange = onPortChange,
                            onConnect = onConnect,
                            onDisconnect = onDisconnect,
                            onStaSsidChange = onStaSsidChange,
                            onStaPasswordChange = onStaPasswordChange,
                            onStaStaticIpChange = onStaStaticIpChange,
                            onStaGatewayChange = onStaGatewayChange,
                            onStaSubnetChange = onStaSubnetChange,
                            onStaDns1Change = onStaDns1Change,
                            onStaDns2Change = onStaDns2Change,
                            onScanWifi = onScanWifi,
                            onApplyStaConfig = onApplyStaConfig,
                            onSwitchToAp = onSwitchToAp,
                            onClearStaConfig = onClearStaConfig
                        )
                        1 -> UsbConnectionContent(
                            connected = usbConnected,
                            onConnect = onConnect,
                            onDisconnect = onDisconnect
                        )
                        2 -> BleConnectionContent(
                            connected = bleConnected,
                            devices = bleDevices,
                            selectedAddress = bleSelectedAddress,
                            scanning = bleScanning,
                            connecting = bleConnecting,
                            permissionRequired = blePermissionRequired,
                            statusMessage = statusMessage,
                            onRequestPermission = onRequestBlePermission,
                            onScan = onScanBle,
                            onSelectDevice = onSelectBleDevice,
                            onConnectSelected = onConnectSelectedBleDevice,
                            onDisconnect = onDisconnect
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiConnectionContent(
    host: String,
    port: String,
    connected: Boolean,
    wifiConfig: WifiConfigUiState,
    discoveredHands: List<DiscoveredHand>,
    discoveryStatus: String,
    onDiscoveredHandClick: (String) -> Unit,
    firmwareIdentified: Boolean,
    wifiProvisioningSupported: Boolean,
    firmwareVersion: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStaSsidChange: (String) -> Unit,
    onStaPasswordChange: (String) -> Unit,
    onStaStaticIpChange: (String) -> Unit,
    onStaGatewayChange: (String) -> Unit,
    onStaSubnetChange: (String) -> Unit,
    onStaDns1Change: (String) -> Unit,
    onStaDns2Change: (String) -> Unit,
    onScanWifi: () -> Unit,
    onApplyStaConfig: () -> Unit,
    onSwitchToAp: () -> Unit,
    onClearStaConfig: () -> Unit
) {
    val canProvisionSta = connected && wifiProvisioningSupported && !wifiConfig.isProvisioning

    Column {
        Text(discoveryStatus, style = MaterialTheme.typography.bodySmall)
        discoveredHands.forEach { hand ->
            Button(
                onClick = { onDiscoveredHandClick(hand.name) },
                enabled = !connected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("${hand.name} · ${hand.host}:${hand.port}")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Host") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                modifier = Modifier.width(110.dp),
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (connected) {
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("断开")
            }
        } else {
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("连接 WiFi")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Dual 配网",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "当前 ${wifiConfig.currentWifiMode} · IP ${wifiConfig.currentIp}" +
                        (wifiConfig.configuredStaSsid?.let { " · STA $it" } ?: "") +
                        (firmwareVersion?.let { " · 固件 $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!canProvisionSta) {
                    Text(
                        text = when {
                            !connected -> "连接设备后可下发 WiFi 配置"
                            !firmwareIdentified -> "正在检测固件能力"
                            !wifiProvisioningSupported -> "当前固件不支持 App 配网，请刷入新版 WebSocket 固件"
                            else -> "正在等待设备确认 WiFi 指令"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = wifiConfig.staSsid,
                        onValueChange = onStaSsidChange,
                        label = { Text("STA WiFi") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                    MiniActionButton(
                        text = if (wifiConfig.isScanning) "扫描中" else "扫描 2.4G",
                        onClick = onScanWifi,
                        enabled = !wifiConfig.isScanning,
                        modifier = Modifier.width(100.dp),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (wifiConfig.scanResults.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        wifiConfig.scanResults.forEach { item ->
                            Surface(
                                onClick = { onStaSsidChange(item.ssid) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (wifiConfig.staSsid == item.ssid) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.ssid, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${item.frequency}MHz · ${item.level}dBm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = wifiConfig.staPassword,
                    onValueChange = onStaPasswordChange,
                    label = { Text("STA 密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !wifiConfig.isProvisioning,
                    shape = RoundedCornerShape(18.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = wifiConfig.staticIp,
                        onValueChange = onStaStaticIpChange,
                        label = { Text("静态 IP") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                    OutlinedTextField(
                        value = wifiConfig.gateway,
                        onValueChange = onStaGatewayChange,
                        label = { Text("网关") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = wifiConfig.subnet,
                        onValueChange = onStaSubnetChange,
                        label = { Text("子网") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                    OutlinedTextField(
                        value = wifiConfig.dns1,
                        onValueChange = onStaDns1Change,
                        label = { Text("DNS1") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                }
                OutlinedTextField(
                    value = wifiConfig.dns2,
                    onValueChange = onStaDns2Change,
                    label = { Text("DNS2") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniActionButton(
                        text = if (wifiConfig.isProvisioning) "等待设备确认" else "下发并切 STA",
                        onClick = onApplyStaConfig,
                        enabled = canProvisionSta && wifiConfig.staSsid.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary
                    )
                    MiniActionButton(
                        text = "切回 AP",
                        onClick = onSwitchToAp,
                        enabled = canProvisionSta,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                MiniActionButton(
                    text = "清除 STA 配置",
                    onClick = onClearStaConfig,
                    enabled = canProvisionSta,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun BleConnectionContent(
    connected: Boolean,
    devices: List<BleScanDevice>,
    selectedAddress: String?,
    scanning: Boolean,
    connecting: Boolean,
    permissionRequired: Boolean,
    statusMessage: String,
    onRequestPermission: () -> Unit,
    onScan: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onConnectSelected: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Bluetooth LE · 加密配对 · 16 字节协议",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (permissionRequired) {
            Text(
                "需要附近设备权限才能扫描并连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("授予蓝牙权限")
            }
        } else if (connected) {
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("断开 BLE") }
        } else if (connecting) {
            Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("取消连接") }
        } else {
            Button(onClick = onScan, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                Text(if (scanning) "正在搜索..." else "扫描 Aero Hand")
            }
        }
        if (!connected && statusMessage.isNotBlank() && statusMessage != "BLE 未连接") {
            Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        devices.forEach { device ->
            Button(
                onClick = { onSelectDevice(device.address) },
                enabled = !connected && !connecting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedAddress == device.address) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (selectedAddress == device.address) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(if (selectedAddress == device.address) "已选择 · ${device.name}" else device.name)
                    Text("${device.address} · RSSI ${device.rssi} dBm", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!connected && !connecting && selectedAddress != null) {
            Button(onClick = onConnectSelected, modifier = Modifier.fillMaxWidth()) {
                Text("连接所选设备")
            }
        }
        if (!connected && !scanning && devices.isEmpty() && !permissionRequired) {
            Text("未发现设备。请确认 Aero Hand 已开机并处于广播状态。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UsbConnectionContent(
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
        ) {
            Text(
                text = "自动扫描 OTG 串口设备，默认使用 921600 波特率。首次连接会弹出系统 USB 授权。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (connected) {
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("断开")
            }
        } else {
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("连接 USB")
            }
        }
    }
}

// ============== 通用组件 ==============

@Composable
fun StatusBadge(text: String, active: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (active) Color(0xFF166534) else Color(0xFF991B1B),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MiniActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    color: Color
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Text(text, fontSize = 12.sp)
    }
}
