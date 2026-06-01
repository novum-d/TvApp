package io.novumd.tvapp.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService

data class BleGattService(
    val uuid: String,
    val characteristics: List<BleGattCharacteristicInfo>,
)

data class BleGattCharacteristicInfo(
    val uuid: String,
    val properties: Int,
)

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

private infix fun Int.hasProperty(property: Int): Boolean = this and property != 0
