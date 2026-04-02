package com.aerohand.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerohand.ui.components.*
import com.aerohand.viewmodel.ConnectionMode
import com.aerohand.viewmodel.HandControlViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandControlScreen(
    viewModel: HandControlViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aero Hand Open - 7DoF Control") },
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
            // Connection Panel
            ConnectionPanel(
                mode = uiState.connectionMode,
                host = uiState.host,
                port = uiState.port,
                wifiConnected = uiState.wifiConnected,
                usbConnected = uiState.usbConnected,
                onModeChange = viewModel::setConnectionMode,
                onHostChange = viewModel::setHost,
                onPortChange = viewModel::setPort,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect
            )

            // Control Panel
            ControlPanel(
                controlValues = uiState.controlValues,
                protocolPreview = uiState.protocolPreview,
                onControlChange = viewModel::updateControlValue,
                onHoming = viewModel::sendHoming,
                onAllZeros = viewModel::sendAllZeros,
                onGetStates = viewModel::requestStates,
                onClearLog = viewModel::clearLogs,
                isConnected = uiState.wifiConnected || uiState.usbConnected
            )

            // Log Panel
            LogPanel(logs = uiState.logs)

            // Bottom spacing
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
