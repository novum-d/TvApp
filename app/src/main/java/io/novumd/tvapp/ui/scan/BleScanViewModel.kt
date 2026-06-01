package io.novumd.tvapp.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.novumd.tvapp.ble.BleConnectionManager
import io.novumd.tvapp.ble.BleConnectionStartResult
import io.novumd.tvapp.ble.BleConnectionStatus
import io.novumd.tvapp.ble.BleGattService
import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.BleScanStartResult
import io.novumd.tvapp.ble.BleScanner
import io.novumd.tvapp.ble.BleServiceDiscoveryStatus
import io.novumd.tvapp.ble.DiscoveredBleDevice
import io.novumd.tvapp.ble.missingBleScanPermissions
import io.novumd.tvapp.ble.upsertDiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class BleScanViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = BleScanner(application.applicationContext)
    private val connectionManager = BleConnectionManager(application.applicationContext)
    private val _uiState = MutableStateFlow(BleScanUiState())

    val uiState: StateFlow<BleScanUiState> = _uiState.asStateFlow()

    fun refreshEnvironmentState() {
        val previousStatus = uiState.value.status
        val missingPermissions = getApplication<Application>().missingBleScanPermissions()
        when {
            missingPermissions.isNotEmpty() -> {
                _uiState.update {
                    it.copy(
                        status = BleScanStatus.PermissionRequired,
                        missingPermissions = missingPermissions,
                        message = "BLE scan permission is required.",
                    )
                }
                if (previousStatus != BleScanStatus.PermissionRequired) {
                    appendLog(
                        scanScreenLog(
                            gattStatus = "permissionDenied",
                            connectionState = "blocked",
                            message = "BLE scan permission is missing",
                        ),
                    )
                }
            }

            !scanner.isBluetoothEnabled() -> {
                _uiState.update {
                    it.copy(
                        status = BleScanStatus.BluetoothOff,
                        missingPermissions = emptyList(),
                        message = "Bluetooth is off.",
                    )
                }
                if (previousStatus != BleScanStatus.BluetoothOff) {
                    appendLog(
                        scanScreenLog(
                            gattStatus = "bluetoothOff",
                            connectionState = "blocked",
                            message = "Bluetooth is off",
                        ),
                    )
                }
            }

            uiState.value.status != BleScanStatus.Scanning -> _uiState.update {
                it.copy(
                    status = BleScanStatus.Stopped,
                    missingPermissions = emptyList(),
                    message = "Ready to scan.",
                )
            }
        }
    }

    fun startScan() {
        when (
            val result = scanner.startScan(
                onDeviceFound = ::onDeviceFound,
                onLog = ::appendLog,
                onScanFailed = ::onScanFailed,
            )
        ) {
            BleScanStartResult.Started -> {
                _uiState.update {
                    it.copy(
                        status = BleScanStatus.Scanning,
                        missingPermissions = emptyList(),
                        message = "Scanning for BLE devices.",
                    )
                }
                appendLog(
                    scanScreenLog(
                        connectionState = "scanning",
                        message = "BLE scan started",
                    ),
                )
            }

            BleScanStartResult.BluetoothOff -> {
                _uiState.update {
                    it.copy(
                        status = BleScanStatus.BluetoothOff,
                        missingPermissions = emptyList(),
                        message = "Bluetooth is off.",
                    )
                }
                appendLog(
                    scanScreenLog(
                        gattStatus = "bluetoothOff",
                        connectionState = "blocked",
                        message = "BLE scan blocked because Bluetooth is off",
                    ),
                )
            }

            BleScanStartResult.ScannerUnavailable -> {
                _uiState.update {
                    it.copy(
                        status = BleScanStatus.Error,
                        message = "BLE scanner is unavailable.",
                    )
                }
                appendLog(
                    scanScreenLog(
                        gattStatus = "scannerUnavailable",
                        connectionState = "error",
                        message = "BluetoothLeScanner is unavailable",
                    ),
                )
            }

            is BleScanStartResult.PermissionMissing -> {
                _uiState.update {
                    it.copy(
                        status = BleScanStatus.PermissionRequired,
                        missingPermissions = result.missingPermissions,
                        message = "BLE scan permission is required.",
                    )
                }
                appendLog(
                    scanScreenLog(
                        gattStatus = "permissionDenied",
                        connectionState = "blocked",
                        message = "BLE scan blocked by missing permission",
                    ),
                )
            }

            is BleScanStartResult.Error -> {
                _uiState.update {
                    it.copy(
                        status = BleScanStatus.Error,
                        message = result.message,
                    )
                }
                appendLog(
                    scanScreenLog(
                        gattStatus = "startError",
                        connectionState = "error",
                        message = result.message,
                    ),
                )
            }
        }
    }

    fun stopScan() {
        scanner.stopScan(::appendLog)
        _uiState.update {
            it.copy(
                status = BleScanStatus.Stopped,
                message = "Scan is stopped.",
            )
        }
    }

    fun updateDeviceNameFilter(query: String) {
        _uiState.update {
            it.copy(deviceNameFilterQuery = query)
        }
    }

    fun clearLogs() {
        _uiState.update {
            it.copy(logs = clearVisibleLogs())
        }
    }

    fun connect(device: DiscoveredBleDevice) {
        if (uiState.value.status == BleScanStatus.Scanning) {
            scanner.stopScan(::appendLog)
            _uiState.update {
                it.copy(
                    status = BleScanStatus.Stopped,
                    message = "Scan stopped before GATT connect.",
                )
            }
        }

        when (
            val result = connectionManager.connect(
                device = device,
                onStateChanged = ::onConnectionStateChanged,
                onServicesChanged = ::onServicesChanged,
                onLog = ::appendLog,
            )
        ) {
            BleConnectionStartResult.Started -> _uiState.update {
                it.copy(
                    selectedDevice = device,
                    connectionStatus = BleConnectionStatus.Connecting,
                    connectionMessage = "Connecting to ${device.name}.",
                    serviceDiscoveryStatus = BleServiceDiscoveryStatus.Idle,
                    serviceDiscoveryMessage = "No discovered services.",
                    services = emptyList(),
                )
            }

            BleConnectionStartResult.BluetoothOff -> {
                _uiState.update {
                    it.copy(
                        selectedDevice = device,
                        connectionStatus = BleConnectionStatus.Failed,
                        connectionMessage = "Bluetooth is off.",
                        serviceDiscoveryStatus = BleServiceDiscoveryStatus.Idle,
                        serviceDiscoveryMessage = "No discovered services.",
                        services = emptyList(),
                    )
                }
                appendLog(
                    connectionScreenLog(
                        gattStatus = "bluetoothOff",
                        connectionState = "blocked",
                        targetDevice = device.address,
                        message = "GATT connect blocked because Bluetooth is off",
                    ),
                )
            }

            is BleConnectionStartResult.ConnectionActive -> appendLog(
                connectionScreenLog(
                    gattStatus = "connectionActive",
                    connectionState = result.status.name,
                    targetDevice = device.address,
                    message = "GATT connect ignored because a connection is active",
                ),
            )

            BleConnectionStartResult.InvalidAddress -> {
                _uiState.update {
                    it.copy(
                        selectedDevice = device,
                        connectionStatus = BleConnectionStatus.Failed,
                        connectionMessage = "Invalid BLE address.",
                        serviceDiscoveryStatus = BleServiceDiscoveryStatus.Idle,
                        serviceDiscoveryMessage = "No discovered services.",
                        services = emptyList(),
                    )
                }
                appendLog(
                    connectionScreenLog(
                        gattStatus = "invalidAddress",
                        connectionState = "failed",
                        targetDevice = device.address,
                        message = "Invalid BLE device address",
                    ),
                )
            }

            is BleConnectionStartResult.PermissionMissing -> {
                _uiState.update {
                    it.copy(
                        selectedDevice = device,
                        connectionStatus = BleConnectionStatus.Failed,
                        connectionMessage = "BLE connect permission is required.",
                        missingPermissions = result.missingPermissions,
                        serviceDiscoveryStatus = BleServiceDiscoveryStatus.Idle,
                        serviceDiscoveryMessage = "No discovered services.",
                        services = emptyList(),
                    )
                }
                appendLog(
                    connectionScreenLog(
                        gattStatus = "permissionDenied",
                        connectionState = "blocked",
                        targetDevice = device.address,
                        message = "GATT connect blocked by missing permission",
                    ),
                )
            }

            is BleConnectionStartResult.Error -> _uiState.update {
                it.copy(
                    selectedDevice = device,
                    connectionStatus = BleConnectionStatus.Failed,
                    connectionMessage = result.message,
                    serviceDiscoveryStatus = BleServiceDiscoveryStatus.Idle,
                    serviceDiscoveryMessage = "No discovered services.",
                    services = emptyList(),
                )
            }
        }
    }

    fun disconnect() {
        connectionManager.disconnect(
            onStateChanged = ::onConnectionStateChanged,
            onLog = ::appendLog,
        )
    }

    fun onPermissionResult() {
        refreshEnvironmentState()
        appendLog(
            scanScreenLog(
                callbackName = "PermissionResult",
                connectionState = uiState.value.status.name,
                message = "Runtime permission result received",
            ),
        )
    }

    override fun onCleared() {
        scanner.stopScan(::appendLog)
        connectionManager.close(::appendLog)
        super.onCleared()
    }

    private fun onDeviceFound(device: DiscoveredBleDevice) {
        _uiState.update {
            it.copy(devices = upsertDiscoveredDevice(it.devices, device))
        }
    }

    private fun onScanFailed(errorCode: Int) {
        _uiState.update {
            it.copy(
                status = BleScanStatus.Error,
                message = "BLE scan failed: $errorCode",
            )
        }
    }

    private fun appendLog(entry: BleLogEntry) {
        _uiState.update {
            it.copy(logs = appendVisibleLog(it.logs, entry))
        }
    }

    private fun onConnectionStateChanged(
        status: BleConnectionStatus,
        message: String,
    ) {
        val shouldClearServices = status == BleConnectionStatus.Disconnected ||
            status == BleConnectionStatus.Failed
        _uiState.update {
            it.copy(
                connectionStatus = status,
                connectionMessage = message,
                selectedDevice = if (status == BleConnectionStatus.Disconnected) {
                    null
                } else {
                    it.selectedDevice
                },
                serviceDiscoveryStatus = if (shouldClearServices) {
                    BleServiceDiscoveryStatus.Idle
                } else {
                    it.serviceDiscoveryStatus
                },
                serviceDiscoveryMessage = if (shouldClearServices) {
                    "No discovered services."
                } else {
                    it.serviceDiscoveryMessage
                },
                services = if (shouldClearServices) {
                    emptyList()
                } else {
                    it.services
                },
            )
        }
    }

    private fun onServicesChanged(
        status: BleServiceDiscoveryStatus,
        services: List<BleGattService>,
        message: String,
    ) {
        _uiState.update {
            it.copy(
                serviceDiscoveryStatus = status,
                serviceDiscoveryMessage = message,
                services = services,
            )
        }
    }
}

private fun scanScreenLog(
    callbackName: String = "BleScanViewModel",
    gattStatus: String = "N/A",
    connectionState: String,
    message: String,
): BleLogEntry {
    return BleLogEntry(
        timestampMillis = System.currentTimeMillis(),
        threadName = Thread.currentThread().name,
        callbackName = callbackName,
        gattStatus = gattStatus,
        connectionState = connectionState,
        operationType = "scan",
        targetDevice = "none",
        characteristicUuid = "N/A",
        message = message,
    )
}

private fun connectionScreenLog(
    callbackName: String = "BleScanViewModel",
    gattStatus: String,
    connectionState: String,
    targetDevice: String,
    message: String,
): BleLogEntry {
    return BleLogEntry(
        timestampMillis = System.currentTimeMillis(),
        threadName = Thread.currentThread().name,
        callbackName = callbackName,
        gattStatus = gattStatus,
        connectionState = connectionState,
        operationType = "connect",
        targetDevice = targetDevice,
        characteristicUuid = "N/A",
        message = message,
    )
}
