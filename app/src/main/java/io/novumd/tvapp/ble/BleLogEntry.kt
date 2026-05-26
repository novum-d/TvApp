package io.novumd.tvapp.ble

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BleLogEntry(
    val timestampMillis: Long,
    val threadName: String,
    val callbackName: String,
    val gattStatus: String,
    val connectionState: String,
    val operationType: String,
    val targetDevice: String,
    val characteristicUuid: String,
    val message: String,
)

fun BleLogEntry.formatForDisplay(): String {
    val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))
    return buildString {
        append(timestamp)
        append(" [")
        append(threadName)
        append("] callback=")
        append(callbackName)
        append(" gattStatus=")
        append(gattStatus)
        append(" state=")
        append(connectionState)
        append(" op=")
        append(operationType)
        append(" target=")
        append(targetDevice)
        append(" characteristic=")
        append(characteristicUuid)
        append(" ")
        append(message)
    }
}
