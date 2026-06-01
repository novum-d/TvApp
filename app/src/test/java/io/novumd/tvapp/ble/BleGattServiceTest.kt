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
}
