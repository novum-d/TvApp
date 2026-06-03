package io.novumd.tvapp.ble

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertEquals
import org.junit.Test

class BleGattServiceTest {
    @Test
    fun characteristicPropertyLabels_returnsReadableLabelsForCombinedProperties() {
        val properties = BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE

        val labels = characteristicPropertyLabels(properties)

        assertEquals(listOf("read", "write", "notify", "indicate"), labels)
    }

    @Test
    fun characteristicPropertyLabels_returnsNoneWhenNoPropertiesAreSet() {
        val labels = characteristicPropertyLabels(0)

        assertEquals(listOf("none"), labels)
    }

    @Test
    fun subscriptionModes_returnsNotifyAndIndicateModesWhenSupported() {
        val characteristic = BleGattCharacteristicInfo(
            uuid = "00002a19-0000-1000-8000-00805f9b34fb",
            properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE,
        )

        val modes = characteristic.subscriptionModes()

        assertEquals(listOf(BleSubscriptionMode.Notification, BleSubscriptionMode.Indication), modes)
    }

    @Test
    fun writeTypes_returnsRequestAndCommandWhenSupported() {
        val characteristic = BleGattCharacteristicInfo(
            uuid = "00002a19-0000-1000-8000-00805f9b34fb",
            properties = BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        )

        val writeTypes = characteristic.writeTypes()

        assertEquals(listOf(BleCharacteristicWriteType.Request, BleCharacteristicWriteType.Command), writeTypes)
    }

    @Test
    fun toDisplayHex_formatsBytesForLogs() {
        val value = byteArrayOf(0x00, 0x0f, 0xff.toByte())

        val hex = value.toDisplayHex()

        assertEquals("00 0F FF", hex)
    }
}
