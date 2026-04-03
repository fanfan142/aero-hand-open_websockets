package com.aerohand.ui.screens

import android.app.Application
import android.view.View
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HandControlScreen() {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: HandControlViewModel = viewModel(
        factory = HandControlViewModelFactory(application)
    )
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Gesture camera service - always created, started/stopped based on page
    val gestureService = remember {
        GestureCameraService(context, lifecycleOwner)
    }

    // Control pager state
    val controlPagerState = rememberPagerState(initialPage = 0) {
        CONTROL_PAGE_TABS.size
    }

    // Connection pager state (WiFi=0, USB=1)
    val connectionPagerState = rememberPagerState(
        initialPage = if (uiState.connectionMode == ConnectionMode.WIFI) 0 else 1
    ) { 2 }

    // Start/stop camera based on current page
    LaunchedEffect(controlPagerState.currentPage) {
        if (controlPagerState.currentPage == 2) { // Gesture page
            // Camera will be started by GestureFollowPage composable
        } else {
            gestureService.stopCamera()
        }
    }

    // Collect gesture state and update viewmodel
    LaunchedEffect(gestureService.state) {
        gestureService.state.collect { state ->
            viewModel.updateGestureCameraState(state)
            // Gesture control is always active when on gesture page and calibrated
            if (controlPagerState.currentPage == 2 &&
                state.calibrationState == com.aerohand.gesture.CalibrationState.CALIBRATED &&
                state.handDetected
            ) {
                val calibratedAngles = gestureService.getCalibratedAngles()
                viewModel.updateControlValuesFromGesture(calibratedAngles)
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
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
                        )
                    )
                )
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top section: Connection panel (1/3 height)
            ConnectionPanel(
                mode = uiState.connectionMode,
                host = uiState.host,
                port = uiState.port,
                wifiConnected = uiState.wifiConnected,
                usbConnected = uiState.usbConnected,
                statusMessage = uiState.statusMessage,
                onModeChange = { mode ->
                    viewModel.setConnectionMode(mode)
                    coroutineScope.launch {
                        connectionPagerState.animateScrollToPage(if (mode == ConnectionMode.WIFI) 0 else 1)
                    }
                },
                onHostChange = viewModel::setHost,
                onPortChange = viewModel::setPort,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect
            )

            // Control pages (2/3~3/4 height)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Tab indicators for control pages
                TabRow(
                    selectedTabIndex = controlPagerState.currentPage,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp)),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicator = {},
                    divider = {}
                ) {
                    CONTROL_PAGE_TABS.forEachIndexed { index, title ->
                        Tab(
                            selected = controlPagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch { controlPagerState.animateScrollToPage(index) }
                            },
                            text = { Text(title) }
                        )
                    }
                }

                // Pager content
                HorizontalPager(
                    state = controlPagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> HomePage(
                            presets = uiState.presetActions,
                            activePresetId = uiState.activePresetId,
                            isRunning = uiState.isPresetRunning,
                            isConnected = uiState.connectionMode != ConnectionMode.GESTURE,
                            onHoming = viewModel::sendHoming,
                            onRunPreset = viewModel::runPreset,
                            modifier = Modifier.padding(top = 12.dp)
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
                            },
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        2 -> GestureFollowPage(
                            gestureService = gestureService,
                            cameraState = uiState.gestureCameraState,
                            onStartCalibration = { gestureService.startCalibration() },
                            onRecordCalibrationPose = { gestureService.recordCalibrationPose() },
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        3 -> LogPage(
                            logs = uiState.logs,
                            onClearLog = viewModel::clearLogs,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
