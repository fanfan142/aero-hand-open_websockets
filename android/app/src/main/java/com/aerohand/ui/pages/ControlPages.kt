package com.aerohand.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerohand.websocket.ControlDefinitions
import com.aerohand.websocket.LogEntry
import com.aerohand.websocket.PresetAction

// ============== Page 1: Home (归位 + 预设动作) ==============

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomePage(
    presets: List<PresetAction>,
    activePresetId: String?,
    isRunning: Boolean,
    isConnected: Boolean,
    onHoming: () -> Unit,
    onRunPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 归位按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onHoming,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    ),
                    enabled = isConnected
                ) {
                    Text("归位")
                }
            }

            Text(
                "预设动作",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // 预设动作网格
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                presets.forEach { preset ->
                    val active = activePresetId == preset.id
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(enabled = !isRunning || active && isConnected) { onRunPreset(preset.id) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                preset.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
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
}

// ============== Page 2: 关节控制 ==============

@Composable
fun JointControlPage(
    controlValues: Map<String, Float>,
    protocolPreview: String,
    onControlChange: (String, Float) -> Unit,
    onAllZeros: () -> Unit,
    onGetStates: () -> Unit,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "7DoF 关节控制",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // 关节滑块
            ControlDefinitions.COMPACT_CONTROLS.forEach { control ->
                JointSlider(
                    label = control.label,
                    value = controlValues[control.id] ?: control.defaultValue,
                    min = control.min,
                    max = control.max,
                    unit = control.unit,
                    onValueChange = { onControlChange(control.id, it) },
                    enabled = isConnected
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 快捷按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAllZeros,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    enabled = isConnected
                ) {
                    Text("All Zero", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onGetStates,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    enabled = isConnected
                ) {
                    Text("Get States", fontSize = 12.sp)
                }
            }

            // 协议预览
            TelemetryPreview(protocolPreview = protocolPreview)
        }
    }
}

@Composable
private fun JointSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    onValueChange: (Float) -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = "${value.toInt()}$unit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(min.toInt().toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = min..max,
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )
                Text(max.toInt().toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TelemetryPreview(protocolPreview: String) {
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

// ============== Page 4: 日志 ==============

@Composable
fun LogPage(
    logs: List<LogEntry>,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "日志",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "最近 50 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onClearLog,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Clear", fontSize = 12.sp)
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 300.dp),
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
                        Text(
                            "暂无日志",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
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
}
