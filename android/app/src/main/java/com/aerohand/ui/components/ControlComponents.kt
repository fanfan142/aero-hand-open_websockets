package com.aerohand.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerohand.viewmodel.ConnectionMode
import com.aerohand.websocket.CompactControl
import com.aerohand.websocket.ControlDefinitions
import com.aerohand.websocket.LogEntry

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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "连接",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

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
                    label = { Text("USB") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val connected = if (mode == ConnectionMode.WIFI) wifiConnected else usbConnected
            val statusColor = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            Text(
                text = statusMessage,
                color = statusColor,
                style = MaterialTheme.typography.bodyMedium
            )

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
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = onPortChange,
                        label = { Text("Port") },
                        modifier = Modifier.weight(0.42f),
                        singleLine = true
                    )
                }
            } else {
                Text(
                    text = "USB OTG 结构已预留；当前交付优先保证 WiFi 版可稳定控制。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (connected) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("断开连接")
                }
            } else {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("连接")
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "7DoF 紧凑控制",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "7 通道输入实时展开为 15 关节 multi_joint_control。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ControlDefinitions.COMPACT_CONTROLS.forEach { control ->
                    SliderCard(
                        control = control,
                        value = controlValues[control.id] ?: control.defaultValue,
                        onValueChange = { onControlChange(control.id, it) },
                        enabled = isConnected
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "15 关节预览",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = protocolPreview,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onHoming,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("Homing", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onAllZeros,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected
                ) {
                    Text("All Zeros", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onGetStates,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected
                ) {
                    Text("Get States", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onClearLog,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear", fontSize = 12.sp)
                }
            }
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
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = control.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${value.toInt()}${control.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = control.min.toInt().toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = control.min..control.max,
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )
                Text(
                    text = control.max.toInt().toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LogPanel(
    logs: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "日志",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 260.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "暂无日志",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        logs.takeLast(50).forEach { entry ->
                            val color = when (entry) {
                                is LogEntry.Send -> MaterialTheme.colorScheme.primary
                                is LogEntry.Receive -> MaterialTheme.colorScheme.tertiary
                                is LogEntry.Error -> MaterialTheme.colorScheme.error
                                is LogEntry.Info -> MaterialTheme.colorScheme.onSurfaceVariant
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
}
