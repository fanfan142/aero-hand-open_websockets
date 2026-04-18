package com.aerohand.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerohand.BuildConfig
import com.aerohand.gesture.GestureCameraService
import com.aerohand.ui.components.ConnectionPanel
import com.aerohand.ui.pages.GestureFollowPage
import com.aerohand.ui.pages.HomePage
import com.aerohand.ui.pages.JointControlPage
import com.aerohand.ui.pages.LogPage
import com.aerohand.viewmodel.ConnectionMode
import com.aerohand.viewmodel.HandControlViewModel

private class HandControlViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HandControlViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HandControlViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

private val CONTROL_PAGE_TABS = listOf("主页", "关节", "手势", "日志")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandControlScreen() {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: HandControlViewModel = viewModel(
        factory = HandControlViewModelFactory(application)
    )
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // Gesture camera service
    val gestureService = remember {
        GestureCameraService(context, lifecycleOwner)
    }

    // Control page selection
    var selectedControlPage by remember { mutableIntStateOf(2) }

    // Connection panel expanded state - default expanded, collapse after connecting
    var connectionPanelExpanded by remember { mutableStateOf(true) }

    // Auto-collapse connection panel when connected
    LaunchedEffect(uiState.wifiConnected, uiState.usbConnected) {
        if (uiState.wifiConnected || uiState.usbConnected) {
            connectionPanelExpanded = false
        }
    }

    // Start/stop camera based on selected page
    LaunchedEffect(selectedControlPage) {
        if (selectedControlPage == 2) { // Gesture page
            // Camera will be started by GestureFollowPage composable
        } else {
            gestureService.stopCamera()
        }
    }

    // Collect gesture state and update viewmodel
    LaunchedEffect(gestureService.state) {
        gestureService.state.collect { state ->
            viewModel.updateGestureCameraState(state)
            if (selectedControlPage == 2 &&
                state.calibrationState == com.aerohand.gesture.CalibrationState.CALIBRATED &&
                state.handDetected &&
                state.targetHandMatched
            ) {
                viewModel.markGestureControlReady()
                val calibratedAngles = gestureService.getCalibratedAngles()
                viewModel.updateControlValuesFromGesture(calibratedAngles)
            } else {
                viewModel.resetGestureSendState()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            gestureService.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Aero Hand Console")
                        Text("v${BuildConfig.VERSION_NAME} · Mobile Console", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    val isConnected = uiState.wifiConnected || uiState.usbConnected
                    val handType = uiState.connectedHandType
                    if (isConnected && handType != null) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (handType == "Left") Color(0xFF3B82F6)
                                    else Color(0xFF10B981)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (handType == "Left") "L" else "R",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Control section with tabs
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Connection panel - collapsible, auto-collapse when connected
                ConnectionPanel(
                    mode = uiState.connectionMode,
                    host = uiState.host,
                    port = uiState.port,
                    wifiConnected = uiState.wifiConnected,
                    usbConnected = uiState.usbConnected,
                    statusMessage = uiState.statusMessage,
                    onModeChange = viewModel::setConnectionMode,
                    onHostChange = viewModel::setHost,
                    onPortChange = viewModel::setPort,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    expanded = connectionPanelExpanded,
                    onToggleExpanded = { connectionPanelExpanded = !connectionPanelExpanded }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Tab indicators
                TabRow(
                    selectedTabIndex = selectedControlPage,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicator = {},
                    divider = {}
                ) {
                    CONTROL_PAGE_TABS.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedControlPage == index,
                            onClick = { selectedControlPage = index },
                            text = { Text(title) }
                        )
                    }
                }

                // Scrollable content for each page
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp)
                ) {
                    when (selectedControlPage) {
                        0 -> HomePage(
                            presets = uiState.presetActions,
                            activePresetId = uiState.activePresetId,
                            isRunning = uiState.isPresetRunning,
                            isConnected = when (uiState.connectionMode) {
                                ConnectionMode.WIFI -> uiState.wifiConnected
                                ConnectionMode.USB -> uiState.usbConnected
                            },
                            onHoming = viewModel::sendHoming,
                            onRunPreset = viewModel::runPreset
                        )
                        1 -> JointControlPage(
                            controlValues = uiState.controlValues,
                            protocolPreview = uiState.protocolPreview,
                            onControlChange = viewModel::updateControlValue,
                            onAllZeros = viewModel::sendAllZeros,
                            onGetStates = viewModel::requestStates,
                            isConnected = when (uiState.connectionMode) {
                                ConnectionMode.WIFI -> uiState.wifiConnected
                                ConnectionMode.USB -> uiState.usbConnected
                            }
                        )
                        2 -> GestureFollowPage(
                            gestureService = gestureService,
                            cameraState = uiState.gestureCameraState,
                            onTargetHandChange = {
                                gestureService.setTargetHand(it)
                                viewModel.setGestureTargetHand(it)
                            },
                            onStartCalibration = { gestureService.startCalibration() },
                            onRecordCalibrationPose = { gestureService.recordCalibrationPose() },
                            onCameraFlip = {}
                        )
                        3 -> LogPage(
                            logs = uiState.logs,
                            onClearLog = viewModel::clearLogs
                        )
                    }
                }
            }
        }
    }
}
