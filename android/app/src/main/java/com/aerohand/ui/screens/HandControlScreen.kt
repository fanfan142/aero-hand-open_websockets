package com.aerohand.ui.screens

import android.app.Application
import android.view.View
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.aerohand.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerohand.gesture.GestureCameraPanel
import com.aerohand.gesture.GestureCameraService
import com.aerohand.ui.components.ConnectionPanel
import com.aerohand.ui.components.ControlPanel
import com.aerohand.ui.components.LogPanel
import com.aerohand.ui.components.PresetPanel
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

    val connected = when (uiState.connectionMode) {
        ConnectionMode.WIFI -> uiState.wifiConnected
        ConnectionMode.USB -> uiState.usbConnected
        ConnectionMode.GESTURE -> uiState.gestureCameraState.handDetected &&
            uiState.gestureCameraState.calibrationState == com.aerohand.gesture.CalibrationState.CALIBRATED
    }

    // Gesture camera service
    val gestureService = remember {
        GestureCameraService(context, lifecycleOwner)
    }

    // Hidden preview view for camera binding
    val hiddenPreviewView = remember { PreviewView(context).apply { visibility = View.GONE } }

    // Start camera when in gesture mode
    LaunchedEffect(uiState.connectionMode) {
        if (uiState.connectionMode == ConnectionMode.GESTURE) {
            gestureService.startCamera(hiddenPreviewView)
        } else {
            gestureService.stopCamera()
        }
    }

    // Collect gesture state and update viewmodel
    LaunchedEffect(gestureService.state) {
        gestureService.state.collect { state ->
            viewModel.updateGestureCameraState(state)
            if (uiState.connectionMode == ConnectionMode.GESTURE &&
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
        Box(
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
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
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
                    onDisconnect = viewModel::disconnect
                )

                // Gesture camera panel (shown when in GESTURE mode)
                if (uiState.connectionMode == ConnectionMode.GESTURE) {
                    GestureCameraPanel(
                        cameraState = uiState.gestureCameraState,
                        onStartCalibration = { gestureService.startCalibration() },
                        onRecordCalibrationPose = { gestureService.recordCalibrationPose() }
                    )
                }

                PresetPanel(
                    presets = uiState.presetActions,
                    activePresetId = uiState.activePresetId,
                    isRunning = uiState.isPresetRunning,
                    onRunPreset = viewModel::runPreset
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(modifier = Modifier.weight(1.1f), color = MaterialTheme.colorScheme.background) {
                        ControlPanel(
                            controlValues = uiState.controlValues,
                            protocolPreview = uiState.protocolPreview,
                            onControlChange = viewModel::updateControlValue,
                            onHoming = viewModel::sendHoming,
                            onAllZeros = viewModel::sendAllZeros,
                            onGetStates = viewModel::requestStates,
                            onClearLog = viewModel::clearLogs,
                            isConnected = connected
                        )
                    }
                    Surface(modifier = Modifier.weight(0.9f), color = MaterialTheme.colorScheme.background) {
                        LogPanel(logs = uiState.logs)
                    }
                }
            }
        }
    }
}
