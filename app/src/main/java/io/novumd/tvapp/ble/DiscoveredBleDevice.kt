package io.novumd.tvapp.ble

data class DiscoveredBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val lastSeenMillis: Long,
)

fun upsertDiscoveredDevice(
    devices: List<DiscoveredBleDevice>,
    device: DiscoveredBleDevice,
): List<DiscoveredBleDevice> {
    val updatedDevices = devices
        .filterNot { it.address == device.address }
        .plus(device)

    return updatedDevices.sortedWith(
        compareByDescending<DiscoveredBleDevice> { it.rssi }
            .thenBy { it.name }
            .thenBy { it.address },
    )
}
