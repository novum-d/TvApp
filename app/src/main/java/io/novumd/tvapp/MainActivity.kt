package io.novumd.tvapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import io.novumd.tvapp.ble.requiredBleScanPermissions
import io.novumd.tvapp.ui.scan.BleScanScreen
import io.novumd.tvapp.ui.scan.BleScanScreenActions
import io.novumd.tvapp.ui.scan.BleScanViewModel
import io.novumd.tvapp.ui.theme.TvAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TvAppTheme {
                val viewModel: BleScanViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    viewModel.onPermissionResult()
                }

                LaunchedEffect(Unit) {
                    viewModel.refreshEnvironmentState()
                }

                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.refreshEnvironmentState()
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose {
                        lifecycle.removeObserver(observer)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BleScanScreen(
                        uiState = uiState,
                        actions = BleScanScreenActions(
                            onStartScan = viewModel::startScan,
                            onStopScan = viewModel::stopScan,
                            onConnectDevice = viewModel::connect,
                            onDisconnectDevice = viewModel::disconnect,
                            onSubscribe = viewModel::subscribe,
                            onUnsubscribe = viewModel::unsubscribe,
                            onWriteCommand = viewModel::writeCommand,
                            onDeviceNameFilterChange = viewModel::updateDeviceNameFilter,
                            onClearLogs = viewModel::clearLogs,
                            onRequestPermissions = {
                                permissionLauncher.launch(requiredBleScanPermissions())
                            },
                        ),
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
