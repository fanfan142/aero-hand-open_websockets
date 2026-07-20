package com.aerohand.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerohand.websocket.ControlDefinitions
import com.aerohand.websocket.ControlTransport
import com.aerohand.websocket.LogEntry
import com.aerohand.websocket.PresetAction
import com.aerohand.websocket.buildProtocolPreview
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============== Page 1: Home (归位 + 预设动作) ==============

@Composable
fun HomePage(
    presets: List<PresetAction>,
    activePresetId: String?,
    isRunning: Boolean,
    isMacroRunning: Boolean,
    isConnected: Boolean,
    presetRepeatCounts: Map<String, Int>,
    macroPresetIds: List<String>,
    onHoming: () -> Unit,
    onRunPreset: (String) -> Unit,
    onCyclePresetRepeat: (String) -> Unit,
    onTogglePresetInMacro: (String) -> Unit,
    onRunMacro: () -> Unit,
    onClearMacro: () -> Unit,
    modifier: Modifier = Modifier
) {
    val macroSummary = macroPresetIds.mapNotNull { presetId ->
        presets.firstOrNull { it.id == presetId }?.let { preset ->
            "${preset.label} X${presetRepeatCounts[presetId] ?: 1}"
        }
    }.joinToString("  ->  ")

    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "常规动作",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                presets.forEach { preset ->
                    PresetActionTile(
                        preset = preset,
                        repeatCount = presetRepeatCounts[preset.id] ?: 1,
                        selectedForMacro = macroPresetIds.contains(preset.id),
                        active = activePresetId == preset.id,
                        isRunning = isRunning,
                        isConnected = isConnected,
                        onRunPreset = { onRunPreset(preset.id) },
                        onCyclePresetRepeat = { onCyclePresetRepeat(preset.id) },
                        onTogglePresetInMacro = { onTogglePresetInMacro(preset.id) }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "宏队列",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (macroSummary.isBlank()) {
                            "未选择动作"
                        } else {
                            "已选 ${macroPresetIds.size} 项：$macroSummary"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onClearMacro,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            enabled = macroPresetIds.isNotEmpty() && !isRunning
                        ) {
                            Text("清空", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onRunMacro,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            enabled = isConnected && macroPresetIds.isNotEmpty() && !isRunning
                        ) {
                            Text(if (isMacroRunning) "执行中" else "执行宏", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetActionTile(
    preset: PresetAction,
    repeatCount: Int,
    selectedForMacro: Boolean,
    active: Boolean,
    isRunning: Boolean,
    isConnected: Boolean,
    onRunPreset: () -> Unit,
    onCyclePresetRepeat: () -> Unit,
    onTogglePresetInMacro: () -> Unit
) {
    val containerColor = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        selectedForMacro -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = isConnected && !isRunning, onClick = onRunPreset),
        shape = RoundedCornerShape(18.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                preset.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(
                onClick = onCyclePresetRepeat,
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isRunning,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("X$repeatCount", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onTogglePresetInMacro,
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isRunning,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text(if (selectedForMacro) "宏中" else "加宏", fontSize = 11.sp)
            }
        }
    }
}

// ============== Page 2: 关节控制 ==============

@Composable
fun JointControlPage(
    controlValues: Map<String, Float>,
    controlTransport: ControlTransport,
    onControlChange: (String, Float) -> Unit,
    onAllZeros: () -> Unit,
    onGetStates: () -> Unit,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val protocolPreview = remember(controlValues, controlTransport) {
        buildProtocolPreview(controlValues, controlTransport)
    }
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
    val context = LocalContext.current
    val recentLogs = logs.takeLast(50)
    val copiedLogText = formatLogText(recentLogs)
    val exportedLogText = formatLogText(logs)

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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val result = runCatching { exportLogsToDownloads(context, exportedLogText) }
                            result.onSuccess { path ->
                                Toast.makeText(context, "已导出: $path", Toast.LENGTH_LONG).show()
                            }.onFailure { error ->
                                Toast.makeText(context, "导出失败: ${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        enabled = logs.isNotEmpty()
                    ) {
                        Text("导出", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Aero Hand logs", copiedLogText))
                            Toast.makeText(context, "已复制日志", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        enabled = recentLogs.isNotEmpty()
                    ) {
                        Text("复制", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onClearLog,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Clear", fontSize = 12.sp)
                    }
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
                        recentLogs.forEach { entry ->
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

private fun formatLogText(logs: List<LogEntry>): String {
    return logs.joinToString(separator = "\n") { entry ->
        "[${entry.timestamp}] ${entry.message}"
    }
}

private fun exportLogsToDownloads(context: Context, logText: String): String {
    val fileName = "aero-hand-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.md"
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/aero"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建日志文件")
        resolver.openOutputStream(uri)?.use { output ->
            output.write(logText.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入日志文件")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Download/aero/$fileName"
    }

    @Suppress("DEPRECATION")
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aero")
    if (!dir.exists() && !dir.mkdirs()) {
        error("无法创建 Download/aero")
    }
    val file = File(dir, fileName)
    file.writeText(logText, Charsets.UTF_8)
    return file.absolutePath
}
