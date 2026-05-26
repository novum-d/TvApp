package io.novumd.tvapp.ui.scan

import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.DiscoveredBleDevice

private const val MAX_VISIBLE_LOG_ENTRIES = 200

data class BleScanUiState(
    val status: BleScanStatus = BleScanStatus.Stopped,
    val devices: List<DiscoveredBleDevice> = emptyList(),
    val logs: List<BleLogEntry> = emptyList(),
    val missingPermissions: List<String> = emptyList(),
    val message: String = "Scan is stopped.",
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
