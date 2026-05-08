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

// ============== 连接面板 ==============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionPanel(
    mode: ConnectionMode,
    host: String,
    port: String,
    wifiConnected: Boolean,
    usbConnected: Boolean,
    statusMessage: String,
    connectedServer: String?,
    wifiConfig: WifiConfigUiState,
    onModeChange: (ConnectionMode) -> Unit,
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
    val connected = if (mode == ConnectionMode.WIFI) wifiConnected else usbConnected
    val gradient = Brush.horizontalGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
    )

    val selectedTab = if (mode == ConnectionMode.WIFI) 0 else 1

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
                            connectedServer = connectedServer,
                            wifiConfig = wifiConfig,
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
    connectedServer: String?,
    wifiConfig: WifiConfigUiState,
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
    val canProvisionSta = connected && connectedServer == "192.168.4.1:8765" && wifiConfig.currentWifiMode.equals("AP", ignoreCase = true)

    Column {
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
                        (wifiConfig.configuredStaSsid?.let { " · STA $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!canProvisionSta) {
                    Text(
                        text = "仅实际连接设备默认 AP 192.168.4.1:8765 时允许下发 WiFi 凭据",
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
                        text = "下发并切 STA",
                        onClick = onApplyStaConfig,
                        enabled = canProvisionSta && wifiConfig.staSsid.isNotBlank() && wifiConfig.staPassword.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary
                    )
                    MiniActionButton(
                        text = "切回 AP",
                        onClick = onSwitchToAp,
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                MiniActionButton(
                    text = "清除 STA 配置",
                    onClick = onClearStaConfig,
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
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
