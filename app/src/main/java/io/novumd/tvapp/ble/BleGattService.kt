package io.novumd.tvapp.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.Locale

data class BleGattService(
    val uuid: String,
    val characteristics: List<BleGattCharacteristicInfo>,
)

data class BleGattCharacteristicInfo(
    val uuid: String,
    val properties: Int,
)

data class BleCharacteristicSubscription(
    val serviceUuid: String,
    val characteristicUuid: String,
    val mode: BleSubscriptionMode,
)

data class BleNotificationEvent(
    val timestampMillis: Long,
    val targetDevice: String,
    val serviceUuid: String,
    val characteristicUuid: String,
    val valueHex: String,
    val byteCount: Int,
)

enum class BleSubscriptionMode {
    Notification,
    Indication,
}

fun BluetoothGattService.toBleGattService(): BleGattService {
    return BleGattService(
        uuid = uuid.toString(),
        characteristics = characteristics.map { characteristic ->
            BleGattCharacteristicInfo(
                uuid = characteristic.uuid.toString(),
                properties = characteristic.properties,
            )
        },
    )
}

fun BleGattCharacteristicInfo.propertyLabels(): List<String> = characteristicPropertyLabels(properties)

fun BleGattCharacteristicInfo.subscriptionModes(): List<BleSubscriptionMode> {
    val modes = mutableListOf<BleSubscriptionMode>()
    if (supportsNotification()) {
        modes += BleSubscriptionMode.Notification
    }
    if (supportsIndication()) {
        modes += BleSubscriptionMode.Indication
    }
    return modes
}

fun BleGattCharacteristicInfo.supportsNotification(): Boolean =
    properties hasProperty BluetoothGattCharacteristic.PROPERTY_NOTIFY

fun BleGattCharacteristicInfo.supportsIndication(): Boolean =
    properties hasProperty BluetoothGattCharacteristic.PROPERTY_INDICATE

fun characteristicPropertyLabels(properties: Int): List<String> {
    val labels = mutableListOf<String>()
    if (properties hasProperty BluetoothGattCharacteristic.PROPERTY_BROADCAST) {
        labels += "broadcast"
    }
    if (properties hasProperty BluetoothGattCharacteristic.PROPERTY_READ) {
        labels += "read"
    }
    if (properties hasProperty BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) {
        labels += "writeWithoutResponse"
    }
    if (properties hasProperty BluetoothGattCharacteristic.PROPERTY_WRITE) {
        labels += "write"
    }
    if (properties hasProperty BluetoothGattCharacteristic.PROPERTY_NOTIFY) {
        labels += "notify"
    }
    if (properties hasProperty BluetoothGattCharacteristic.PROPERTY_INDICATE) {
        labels += "indicate"
    }
    if (properties hasProperty BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) {
        labels += "signedWrite"
    }
    if (properties hasProperty BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) {
        labels += "extendedProps"
    }
    return labels.ifEmpty { listOf("none") }
}

fun ByteArray.toDisplayHex(): String {
    if (isEmpty()) {
        return "empty"
    }

    return joinToString(separator = " ") { byte ->
        String.format(Locale.US, "%02X", byte)
    }
}

infix fun Int.hasProperty(property: Int): Boolean = this and property != 0
