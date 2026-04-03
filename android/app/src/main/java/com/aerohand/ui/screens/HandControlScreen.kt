package com.aerohand.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerohand.ui.components.ConnectionPanel
import com.aerohand.ui.components.ControlPanel
import com.aerohand.ui.components.LogPanel
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aero Hand Open") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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

            ControlPanel(
                controlValues = uiState.controlValues,
                protocolPreview = uiState.protocolPreview,
                onControlChange = viewModel::updateControlValue,
                onHoming = viewModel::sendHoming,
                onAllZeros = viewModel::sendAllZeros,
                onGetStates = viewModel::requestStates,
                onClearLog = viewModel::clearLogs,
                isConnected = if (uiState.connectionMode == com.aerohand.viewmodel.ConnectionMode.WIFI) {
                    uiState.wifiConnected
                } else {
                    uiState.usbConnected
                }
            )

            LogPanel(logs = uiState.logs)
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
