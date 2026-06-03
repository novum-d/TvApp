package io.novumd.tvapp.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.novumd.tvapp.ble.BleCharacteristicSubscription
import io.novumd.tvapp.ble.BleConnectionManager
import io.novumd.tvapp.ble.BleConnectionStartResult
import io.novumd.tvapp.ble.BleConnectionStatus
import io.novumd.tvapp.ble.BleGattService
import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.BleNotificationEvent
import io.novumd.tvapp.ble.BleScanStartResult
import io.novumd.tvapp.ble.BleScanner
import io.novumd.tvapp.ble.BleServiceDiscoveryStatus
import io.novumd.tvapp.ble.BleSubscriptionMode
import io.novumd.tvapp.ble.BleSubscriptionStartResult
import io.novumd.tvapp.ble.BleSubscriptionStatus
import io.novumd.tvapp.ble.DiscoveredBleDevice
import io.novumd.tvapp.ble.missingBleScanPermissions
import io.novumd.tvapp.ble.upsertDiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Suppress("TooManyFunctions")
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
                onSubscriptionChanged = ::onSubscriptionChanged,
                onNotificationReceived = ::onNotificationReceived,
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
                    subscriptionStatus = BleSubscriptionStatus.Idle,
                    subscriptionMessage = "No active notification subscription.",
                    activeSubscription = null,
                    lastNotification = null,
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

    fun subscribe(
        serviceUuid: String,
        characteristicUuid: String,
        mode: BleSubscriptionMode,
    ) {
        val subscription = BleCharacteristicSubscription(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            mode = mode,
        )
        when (
            val result = connectionManager.subscribeToCharacteristic(
                subscription = subscription,
                onSubscriptionChanged = ::onSubscriptionChanged,
                onLog = ::appendLog,
            )
        ) {
            // 購読に成功した場合は、後続のコールバックにより処理されるので何もしない
            BleSubscriptionStartResult.Started -> Unit
            BleSubscriptionStartResult.CccdUnavailable -> reportSubscriptionStartBlocked(
                subscription = subscription,
                gattStatus = "cccdUnavailable",
                message = "CCCD descriptor is unavailable.",
            )

            BleSubscriptionStartResult.CharacteristicUnavailable -> reportSubscriptionStartBlocked(
                subscription = subscription,
                gattStatus = "characteristicUnavailable",
                message = "Characteristic is unavailable.",
            )

            BleSubscriptionStartResult.DescriptorWriteNotStarted -> Unit
            is BleSubscriptionStartResult.Error -> reportSubscriptionStartBlocked(
                subscription = subscription,
                gattStatus = "descriptorWriteError",
                message = result.message,
            )

            BleSubscriptionStartResult.LocalNotificationFailed -> Unit
            BleSubscriptionStartResult.NoActiveGatt -> reportSubscriptionStartBlocked(
                subscription = subscription,
                gattStatus = "noActiveGatt",
                message = "No active GATT connection.",
            )

            BleSubscriptionStartResult.NoActiveSubscription -> reportSubscriptionStartBlocked(
                subscription = subscription,
                gattStatus = "noActiveSubscription",
                message = "No active subscription.",
            )

            BleSubscriptionStartResult.OperationActive -> reportSubscriptionStartBlocked(
                subscription = subscription,
                gattStatus = "operationActive",
                message = "Another subscribe operation is active.",
            )

            is BleSubscriptionStartResult.NotConnected -> reportSubscriptionStartBlocked(
                subscription = subscription,
                gattStatus = "notConnected",
                message = "GATT is not connected: ${result.status.name}.",
            )

            is BleSubscriptionStartResult.PermissionMissing -> {
                _uiState.update {
                    it.copy(
                        missingPermissions = result.missingPermissions,
                        subscriptionStatus = BleSubscriptionStatus.Failed,
                        subscriptionMessage = "BLE connect permission is required.",
                    )
                }
                appendLog(
                    subscriptionScreenLog(
                        gattStatus = "permissionDenied",
                        connectionState = "blocked",
                        subscription = subscription,
                        message = "Subscribe blocked by missing permission",
                    ),
                )
            }

            BleSubscriptionStartResult.UnsupportedProperty -> reportSubscriptionStartBlocked(
                subscription = subscription,
                gattStatus = "unsupportedProperty",
                message = "Characteristic does not support ${mode.name.lowercase()}.",
            )
        }
    }

    fun unsubscribe() {
        val currentSubscription = uiState.value.activeSubscription
        when (
            val result = connectionManager.unsubscribeFromActiveCharacteristic(
                onSubscriptionChanged = ::onSubscriptionChanged,
                onLog = ::appendLog,
            )
        ) {
            BleSubscriptionStartResult.Started -> Unit
            BleSubscriptionStartResult.CccdUnavailable -> reportSubscriptionStartBlocked(
                subscription = currentSubscription,
                gattStatus = "cccdUnavailable",
                message = "CCCD descriptor is unavailable.",
            )

            BleSubscriptionStartResult.CharacteristicUnavailable -> reportSubscriptionStartBlocked(
                subscription = currentSubscription,
                gattStatus = "characteristicUnavailable",
                message = "Characteristic is unavailable.",
            )

            BleSubscriptionStartResult.DescriptorWriteNotStarted -> Unit
            is BleSubscriptionStartResult.Error -> reportSubscriptionStartBlocked(
                subscription = currentSubscription,
                gattStatus = "descriptorWriteError",
                message = result.message,
            )

            BleSubscriptionStartResult.LocalNotificationFailed -> Unit
            BleSubscriptionStartResult.NoActiveGatt -> reportSubscriptionStartBlocked(
                subscription = currentSubscription,
                gattStatus = "noActiveGatt",
                message = "No active GATT connection.",
            )

            BleSubscriptionStartResult.NoActiveSubscription -> reportSubscriptionStartBlocked(
                subscription = currentSubscription,
                gattStatus = "noActiveSubscription",
                message = "No active subscription.",
            )

            BleSubscriptionStartResult.OperationActive -> reportSubscriptionStartBlocked(
                subscription = currentSubscription,
                gattStatus = "operationActive",
                message = "Another subscribe operation is active.",
            )

            is BleSubscriptionStartResult.NotConnected -> reportSubscriptionStartBlocked(
                subscription = currentSubscription,
                gattStatus = "notConnected",
                message = "GATT is not connected: ${result.status.name}.",
            )

            is BleSubscriptionStartResult.PermissionMissing -> {
                _uiState.update {
                    it.copy(
                        missingPermissions = result.missingPermissions,
                        subscriptionStatus = BleSubscriptionStatus.Failed,
                        subscriptionMessage = "BLE connect permission is required.",
                    )
                }
                appendLog(
                    subscriptionScreenLog(
                        gattStatus = "permissionDenied",
                        connectionState = "blocked",
                        subscription = currentSubscription,
                        message = "Unsubscribe blocked by missing permission",
                    ),
                )
            }

            BleSubscriptionStartResult.UnsupportedProperty -> reportSubscriptionStartBlocked(
                subscription = currentSubscription,
                gattStatus = "unsupportedProperty",
                message = "Characteristic does not support the active subscription mode.",
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
                subscriptionStatus = if (shouldClearServices) {
                    BleSubscriptionStatus.Idle
                } else {
                    it.subscriptionStatus
                },
                subscriptionMessage = if (shouldClearServices) {
                    "No active notification subscription."
                } else {
                    it.subscriptionMessage
                },
                activeSubscription = if (shouldClearServices) {
                    null
                } else {
                    it.activeSubscription
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

    private fun onSubscriptionChanged(
        status: BleSubscriptionStatus,
        subscription: BleCharacteristicSubscription?,
        message: String,
    ) {
        _uiState.update {
            it.copy(
                subscriptionStatus = status,
                activeSubscription = subscription,
                subscriptionMessage = message,
            )
        }
    }

    private fun onNotificationReceived(event: BleNotificationEvent) {
        _uiState.update {
            it.copy(lastNotification = event)
        }
    }

    private fun reportSubscriptionStartBlocked(
        subscription: BleCharacteristicSubscription?,
        gattStatus: String,
        message: String,
    ) {
        _uiState.update {
            it.copy(
                subscriptionStatus = BleSubscriptionStatus.Failed,
                subscriptionMessage = message,
            )
        }
        appendLog(
            subscriptionScreenLog(
                gattStatus = gattStatus,
                connectionState = uiState.value.connectionStatus.name,
                subscription = subscription,
                message = message,
            ),
        )
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

private fun subscriptionScreenLog(
    callbackName: String = "BleScanViewModel",
    gattStatus: String,
    connectionState: String,
    subscription: BleCharacteristicSubscription?,
    message: String,
): BleLogEntry {
    return BleLogEntry(
        timestampMillis = System.currentTimeMillis(),
        threadName = Thread.currentThread().name,
        callbackName = callbackName,
        gattStatus = gattStatus,
        connectionState = connectionState,
        operationType = "subscribe",
        targetDevice = "active",
        characteristicUuid = subscription?.characteristicUuid ?: "N/A",
        message = message,
    )
}
