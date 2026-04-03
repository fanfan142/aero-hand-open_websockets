package com.aerohand.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerohand.viewmodel.ConnectionMode
import com.aerohand.websocket.CompactControl
import com.aerohand.websocket.ControlDefinitions
import com.aerohand.websocket.LogEntry
import com.aerohand.websocket.PresetAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionPanel(
    mode: ConnectionMode,
    host: String,
    port: String,
    wifiConnected: Boolean,
    usbConnected: Boolean,
    statusMessage: String,
    onModeChange: (ConnectionMode) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = if (mode == ConnectionMode.WIFI) wifiConnected else usbConnected
    val gradient = Brush.horizontalGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
    )

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
                StatusBadge(if (connected) "ONLINE" else "OFFLINE", connected)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = mode == ConnectionMode.WIFI,
                    onClick = { onModeChange(ConnectionMode.WIFI) },
                    label = { Text("WiFi") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = mode == ConnectionMode.USB,
                    onClick = { onModeChange(ConnectionMode.USB) },
                    label = { Text("USB OTG") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (mode == ConnectionMode.WIFI) {
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
            } else {
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
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("断开")
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(if (mode == ConnectionMode.WIFI) "连接 WiFi" else "连接 USB")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetPanel(
    presets: List<PresetAction>,
    activePresetId: String?,
    isRunning: Boolean,
    onRunPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    DashboardCard(title = "预设动作", subtitle = "SDK 同源动作库", modifier = modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3
        ) {
            presets.forEach { preset ->
                val active = activePresetId == preset.id
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(enabled = !isRunning || active) { onRunPreset(preset.id) },
                    shape = RoundedCornerShape(18.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(preset.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            preset.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ControlPanel(
    controlValues: Map<String, Float>,
    protocolPreview: String,
    onControlChange: (String, Float) -> Unit,
    onHoming: () -> Unit,
    onAllZeros: () -> Unit,
    onGetStates: () -> Unit,
    onClearLog: () -> Unit,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    DashboardCard(title = "7DoF 控制", subtitle = "实时展开为协议帧", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ControlDefinitions.COMPACT_CONTROLS.forEach { control ->
                SliderCard(
                    control = control,
                    value = controlValues[control.id] ?: control.defaultValue,
                    onValueChange = { onControlChange(control.id, it) },
                    enabled = isConnected
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniActionButton("Homing", onHoming, isConnected, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
            MiniActionButton("All Zero", onAllZeros, isConnected, Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
            MiniActionButton("Get States", onGetStates, isConnected, Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            OutlinedButton(
                onClick = onClearLog,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Clear", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        TelemetryPanel(protocolPreview = protocolPreview)
    }
}

@Composable
private fun TelemetryPanel(protocolPreview: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0D1B2A)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("协议预览", color = Color(0xFF7DD3FC), style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = protocolPreview,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFFE2E8F0)
            )
        }
    }
}

@Composable
fun SliderCard(
    control: CompactControl,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = control.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = "${value.toInt()}${control.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(control.min.toInt().toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = control.min..control.max,
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )
                Text(control.max.toInt().toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LogPanel(
    logs: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    DashboardCard(title = "日志", subtitle = "最近 50 条", modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 220.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF08111F)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("暂无日志", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF64748B))
                } else {
                    logs.takeLast(50).forEach { entry ->
                        val color = when (entry) {
                            is LogEntry.Send -> Color(0xFF38BDF8)
                            is LogEntry.Receive -> Color(0xFF34D399)
                            is LogEntry.Error -> Color(0xFFF87171)
                            is LogEntry.Info -> Color(0xFFCBD5E1)
                        }
                        Text(
                            text = "[${entry.timestamp}] ${entry.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = color,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        })
    }
}

@Composable
private fun StatusBadge(text: String, active: Boolean) {
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
