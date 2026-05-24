package io.novumd.tvapp.ui.scan

import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.DiscoveredBleDevice

private const val MaxVisibleLogEntries = 200

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
): List<BleLogEntry> {
    return listOf(entry).plus(logs).take(MaxVisibleLogEntries)
}
