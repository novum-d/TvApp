package io.novumd.tvapp.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveredBleDeviceTest {
    @Test
    fun upsertDiscoveredDevice_replacesExistingAddressAndSortsByStrongestRssi() {
        val firstDevice = DiscoveredBleDevice(
            name = "Living Room TV",
            address = "00:11:22:33:44:55",
            rssi = -80,
            lastSeenMillis = 1L,
        )
        val secondDevice = DiscoveredBleDevice(
            name = "Speaker",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -50,
            lastSeenMillis = 2L,
        )
        val updatedFirstDevice = firstDevice.copy(rssi = -40, lastSeenMillis = 3L)

        val devices = upsertDiscoveredDevice(
            devices = listOf(firstDevice, secondDevice),
            device = updatedFirstDevice,
        )

        assertEquals(listOf(updatedFirstDevice, secondDevice), devices)
    }
}
