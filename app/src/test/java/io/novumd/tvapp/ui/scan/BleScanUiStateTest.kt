package io.novumd.tvapp.ui.scan

import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.DiscoveredBleDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class BleScanUiStateTest {
    @Test
    fun appendVisibleLog_prependsNewestEntryAndKeepsLatestTwoHundred() {
        val existingLogs = (0 until 200).map { index ->
            logEntry(timestampMillis = index.toLong())
        }
        val newestLog = logEntry(timestampMillis = 999L)

        val logs = appendVisibleLog(existingLogs, newestLog)

        assertEquals(200, logs.size)
        assertEquals(newestLog, logs.first())
        assertEquals(198L, logs.last().timestampMillis)
    }

    @Test
    fun filterDevicesByName_matchesDeviceNameIgnoringCase() {
        val devices = listOf(
            discoveredDevice(name = "Living Room TV", address = "00:11:22:33:44:55"),
            discoveredDevice(name = "Speaker", address = "AA:BB:CC:DD:EE:FF"),
        )

        val filteredDevices = filterDevicesByName(devices, "tv")

        assertEquals(listOf(devices[0]), filteredDevices)
    }

    @Test
    fun filterDevicesByName_doesNotMatchAddress() {
        val devices = listOf(
            discoveredDevice(name = "Living Room TV", address = "00:11:22:33:44:55"),
            discoveredDevice(name = "Speaker", address = "AA:BB:CC:DD:EE:FF"),
        )

        val filteredDevices = filterDevicesByName(devices, "bb:cc")

        assertEquals(emptyList<DiscoveredBleDevice>(), filteredDevices)
    }

    @Test
    fun filterDevicesByName_returnsAllDevicesForBlankQuery() {
        val devices = listOf(
            discoveredDevice(name = "Living Room TV", address = "00:11:22:33:44:55"),
            discoveredDevice(name = "Speaker", address = "AA:BB:CC:DD:EE:FF"),
        )

        val filteredDevices = filterDevicesByName(devices, " ")

        assertEquals(devices, filteredDevices)
    }

    private fun logEntry(timestampMillis: Long): BleLogEntry {
        return BleLogEntry(
            timestampMillis = timestampMillis,
            threadName = "main",
            callbackName = "ScanCallback.onScanResult",
            gattStatus = "N/A",
            connectionState = "scanning",
            operationType = "scan",
            targetDevice = "00:11:22:33:44:55",
            characteristicUuid = "N/A",
            message = "rssi=-60",
        )
    }

    private fun discoveredDevice(
        name: String,
        address: String,
    ): DiscoveredBleDevice {
        return DiscoveredBleDevice(
            name = name,
            address = address,
            rssi = -60,
            lastSeenMillis = 0L,
        )
    }
}
