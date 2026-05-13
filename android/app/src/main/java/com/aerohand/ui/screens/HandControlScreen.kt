package com.aerohand.ui.screens

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.IconButton
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
import com.aerohand.viewmodel.ConnectionPanelVisibility
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
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.scanWifiNetworks()
    }
    val requestWifiScan = {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
        wifiPermissionLauncher.launch(permissions)
    }

    // Gesture camera service
    val gestureService = remember {
        GestureCameraService(context, lifecycleOwner)
    }

    var selectedControlPage by remember { mutableIntStateOf(2) }

    LaunchedEffect(uiState.wifiConnected, uiState.usbConnected) {
        if ((uiState.wifiConnected || uiState.usbConnected) &&
            uiState.connectionPanelVisibility == ConnectionPanelVisibility.EXPANDED
        ) {
            viewModel.setConnectionPanelVisibility(ConnectionPanelVisibility.COLLAPSED)
        }
    }

    // Start/stop camera based on selected page
    LaunchedEffect(selectedControlPage) {
        if (selectedControlPage == 2) { // Gesture page
            // Camera will be started by GestureFollowPage composable
        } else {
            gestureService.stopCamera()
            viewModel.resetGestureSendState()
        }
    }

    // Collect throttled gesture UI state.
    LaunchedEffect(gestureService.state) {
        gestureService.state.collect { state ->
            viewModel.updateGestureCameraState(state)
        }
    }

    // Keep real-time control independent from Compose UI refresh rate.
    LaunchedEffect(gestureService.controlFrame, selectedControlPage) {
        gestureService.controlFrame.collect { frame ->
            if (selectedControlPage == 2) {
                if (frame.allowed) {
                    viewModel.markGestureControlReady()
                    viewModel.updateControlValuesFromGesture(frame.angles)
                } else {
                    viewModel.resetGestureSendState()
                }
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
                        val connectionLabel = when (uiState.connectionMode) {
                            ConnectionMode.WIFI -> if (uiState.wifiConnected) {
                                "WiFi · ${uiState.connectedServer ?: "${uiState.host}:${uiState.port}"}"
                            } else {
                                "WiFi · 未连接"
                            }
                            ConnectionMode.USB -> if (uiState.usbConnected) {
                                "USB · 921600"
                            } else {
                                "USB · 未连接"
                            }
                        }
                        Text(
                            "v${BuildConfig.VERSION_NAME} · $connectionLabel",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::cycleConnectionPanelVisibility
                    ) {
                        Text(
                            text = when (uiState.connectionPanelVisibility) {
                                ConnectionPanelVisibility.EXPANDED -> "收"
                                ConnectionPanelVisibility.COLLAPSED -> "展"
                                ConnectionPanelVisibility.HIDDEN -> "显"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                if (uiState.connectionPanelVisibility != ConnectionPanelVisibility.HIDDEN) {
                    ConnectionPanel(
                        mode = uiState.connectionMode,
                        host = uiState.host,
                        port = uiState.port,
                        wifiConnected = uiState.wifiConnected,
                        usbConnected = uiState.usbConnected,
                        statusMessage = uiState.statusMessage,
                        connectedServer = uiState.connectedServer,
                        wifiConfig = uiState.wifiConfig,
                        onModeChange = viewModel::setConnectionMode,
                        onHostChange = viewModel::setHost,
                        onPortChange = viewModel::setPort,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                        onStaSsidChange = viewModel::setStaSsid,
                        onStaPasswordChange = viewModel::setStaPassword,
                        onStaStaticIpChange = viewModel::setStaStaticIp,
                        onStaGatewayChange = viewModel::setStaGateway,
                        onStaSubnetChange = viewModel::setStaSubnet,
                        onStaDns1Change = viewModel::setStaDns1,
                        onStaDns2Change = viewModel::setStaDns2,
                        onScanWifi = requestWifiScan,
                        onApplyStaConfig = viewModel::applyStaConfig,
                        onSwitchToAp = viewModel::switchDeviceToAp,
                        onClearStaConfig = viewModel::clearStaConfig,
                        expanded = uiState.connectionPanelVisibility == ConnectionPanelVisibility.EXPANDED
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }

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
                            onCameraFlip = { viewModel.resetGestureSendState() }
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
