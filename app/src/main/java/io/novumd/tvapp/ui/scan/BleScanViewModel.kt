package io.novumd.tvapp.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.BleScanStartResult
import io.novumd.tvapp.ble.BleScanner
import io.novumd.tvapp.ble.DiscoveredBleDevice
import io.novumd.tvapp.ble.missingBleScanPermissions
import io.novumd.tvapp.ble.upsertDiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class BleScanViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = BleScanner(application.applicationContext)
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

            uiState.value.status != BleScanStatus.Scanning -> {
                _uiState.update {
                    it.copy(
                        status = BleScanStatus.Stopped,
                        missingPermissions = emptyList(),
                        message = "Ready to scan.",
                    )
                }
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
}
