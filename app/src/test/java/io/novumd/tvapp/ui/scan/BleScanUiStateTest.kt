package io.novumd.tvapp.ui.scan

import io.novumd.tvapp.ble.BleLogEntry
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
}
