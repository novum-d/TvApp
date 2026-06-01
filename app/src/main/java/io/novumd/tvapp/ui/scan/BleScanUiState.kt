package io.novumd.tvapp.ui.scan

import io.novumd.tvapp.ble.BleConnectionStatus
import io.novumd.tvapp.ble.BleGattService
import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.BleServiceDiscoveryStatus
import io.novumd.tvapp.ble.DiscoveredBleDevice

private const val MAX_VISIBLE_LOG_ENTRIES = 200

data class BleScanUiState(
    val status: BleScanStatus = BleScanStatus.Stopped,
    val devices: List<DiscoveredBleDevice> = emptyList(),
    val deviceNameFilterQuery: String = "",
    val logs: List<BleLogEntry> = emptyList(),
    val missingPermissions: List<String> = emptyList(),
    val message: String = "Scan is stopped.",
    val selectedDevice: DiscoveredBleDevice? = null,
    val connectionStatus: BleConnectionStatus = BleConnectionStatus.Disconnected,
    val connectionMessage: String = "No active GATT connection.",
    val serviceDiscoveryStatus: BleServiceDiscoveryStatus = BleServiceDiscoveryStatus.Idle,
    val serviceDiscoveryMessage: String = "No discovered services.",
    val services: List<BleGattService> = emptyList(),
)

enum class BleScanStatus {
    Stopped,
    Scanning,
    PermissionRequired,
    BluetoothOff,
    Error,
}

fun appendVisibleLog(
    logs: List<BleLogEntry>,
    entry: BleLogEntry,
): List<BleLogEntry> = listOf(entry).plus(logs).take(MAX_VISIBLE_LOG_ENTRIES)

fun clearVisibleLogs(): List<BleLogEntry> = emptyList()

fun filterDevicesByName(
    devices: List<DiscoveredBleDevice>,
    query: String,
): List<DiscoveredBleDevice> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
        return devices
    }

    return devices.filter { device ->
        device.name.contains(normalizedQuery, ignoreCase = true)
    }
}
