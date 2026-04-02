package com.aerohand.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerohand.websocket.ControlDefinitions
import com.aerohand.websocket.LogEntry

@Composable
fun ConnectionPanel(
    mode: com.aerohand.viewmodel.ConnectionMode,
    host: String,
    port: String,
    wifiConnected: Boolean,
    usbConnected: Boolean,
    onModeChange: (com.aerohand.viewmodel.ConnectionMode) -> Unit,
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
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Connection Mode Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = mode == com.aerohand.viewmodel.ConnectionMode.WIFI,
                    onClick = { onModeChange(com.aerohand.viewmodel.ConnectionMode.WIFI) },
                    label = { Text("WiFi") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = mode == com.aerohand.viewmodel.ConnectionMode.USB,
                    onClick = { onModeChange(com.aerohand.viewmodel.ConnectionMode.USB) },
                    label = { Text("USB") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connection Status
            val statusText = when {
                mode == com.aerohand.viewmodel.ConnectionMode.WIFI && wifiConnected -> "Connected"
                mode == com.aerohand.viewmodel.ConnectionMode.USB && usbConnected -> "USB Connected"
                else -> "Disconnected"
            }
            val statusColor = if (wifiConnected || usbConnected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error

            Text(
                text = "Status: $statusText",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (mode == com.aerohand.viewmodel.ConnectionMode.WIFI) {
                // WiFi inputs
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
                        modifier = Modifier.weight(0.4f),
                        singleLine = true
                    )
                }
            } else {
                Text(
                    text = "USB Serial: Connect via USB OTG cable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connect/Disconnect Button
            if (wifiConnected || usbConnected) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Disconnect")
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect")
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
                text = "7DoF Control",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "SDK-style compact control → expanded to 15 joints",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Sliders
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

            // Protocol Preview
            Text(
                text = "Expanded 15-Joint Preview",
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

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onHoming,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
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
    control: com.aerohand.websocket.CompactControl,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = control.label,
                    style = MaterialTheme.typography.bodyMedium
                )
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
                    text = "${control.min.toInt()}",
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
                    text = "${control.max.toInt()}",
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
                text = "Log",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 250.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth()
                ) {
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
