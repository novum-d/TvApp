package io.novumd.tvapp.ui.scan

import android.bluetooth.BluetoothGattCharacteristic
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.novumd.tvapp.ble.BleConnectionStatus
import io.novumd.tvapp.ble.BleGattCharacteristicInfo
import io.novumd.tvapp.ble.BleGattService
import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.BleServiceDiscoveryStatus
import io.novumd.tvapp.ble.DiscoveredBleDevice
import io.novumd.tvapp.ble.formatForDisplay
import io.novumd.tvapp.ble.propertyLabels
import io.novumd.tvapp.ui.theme.TvAppTheme

private val DeviceListHeight = 360.dp
private val ServiceListHeight = 280.dp
private val LogListHeight = 96.dp

@Composable
fun BleScanScreen(
    uiState: BleScanUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (DiscoveredBleDevice) -> Unit,
    onDisconnectDevice: () -> Unit,
    onDeviceNameFilterChange: (String) -> Unit,
    onClearLogs: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredDevices = filterDevicesByName(
        devices = uiState.devices,
        query = uiState.deviceNameFilterQuery,
    )

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "scan_header") {
            ScanHeader(
                uiState = uiState,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onRequestPermissions = onRequestPermissions,
            )
        }

        item(key = "device_divider") {
            HorizontalDivider()
        }

        item(key = "device_title") {
            Text(
                text = "Detected Devices (${filteredDevices.size}/${uiState.devices.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item(key = "device_filter") {
            OutlinedTextField(
                value = uiState.deviceNameFilterQuery,
                onValueChange = onDeviceNameFilterChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = {
                    Text("Filter by device name")
                },
            )
        }
        item(key = "device_list") {
            DeviceList(
                devices = filteredDevices,
                hasAnyDevices = uiState.devices.isNotEmpty(),
                filterQuery = uiState.deviceNameFilterQuery,
                selectedDevice = uiState.selectedDevice,
                connectionStatus = uiState.connectionStatus,
                connectionMessage = uiState.connectionMessage,
                onConnectDevice = onConnectDevice,
                onDisconnectDevice = onDisconnectDevice,
            )
        }

        item(key = "service_divider") {
            HorizontalDivider()
        }

        item(key = "service_panel") {
            ServiceDiscoveryPanel(
                status = uiState.serviceDiscoveryStatus,
                message = uiState.serviceDiscoveryMessage,
                services = uiState.services,
            )
        }

        item(key = "log_divider") {
            HorizontalDivider()
        }

        item(key = "log_header") {
            LogHeader(
                hasLogs = uiState.logs.isNotEmpty(),
                onClearLogs = onClearLogs,
            )
        }
        item(key = "log_list") {
            LogList(logs = uiState.logs)
        }
    }
}

@Composable
private fun ScanHeader(
    uiState: BleScanUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "BLE Scan",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "State: ${uiState.status.name}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = uiState.message,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (uiState.missingPermissions.isNotEmpty()) {
            Text(
                text = "Missing: ${uiState.missingPermissions.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onStartScan,
                enabled = uiState.status != BleScanStatus.Scanning,
            ) {
                Text("Start Scan")
            }
            OutlinedButton(
                onClick = onStopScan,
                enabled = uiState.status == BleScanStatus.Scanning,
            ) {
                Text("Stop Scan")
            }
        }
        if (uiState.status == BleScanStatus.PermissionRequired) {
            OutlinedButton(onClick = onRequestPermissions) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<DiscoveredBleDevice>,
    hasAnyDevices: Boolean,
    filterQuery: String,
    selectedDevice: DiscoveredBleDevice?,
    connectionStatus: BleConnectionStatus,
    connectionMessage: String,
    onConnectDevice: (DiscoveredBleDevice) -> Unit,
    onDisconnectDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (devices.isEmpty()) {
        Text(
            text = if (hasAnyDevices && filterQuery.isNotBlank()) {
                "No devices match the name filter."
            } else {
                "No BLE devices detected."
            },
            modifier = modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .height(DeviceListHeight),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = devices,
            key = { device -> device.address },
        ) { device ->
            val isSelected = selectedDevice?.address == device.address
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "RSSI: ${device.rssi} dBm",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (isSelected) {
                        Text(
                            text = "Connection: ${connectionStatus.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = connectionMessage,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { onConnectDevice(device) },
                            enabled = connectionStatus.canStartConnect(),
                        ) {
                            Text("Connect")
                        }
                        OutlinedButton(
                            onClick = onDisconnectDevice,
                            enabled = connectionStatus.canDisconnect(isSelected),
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceDiscoveryPanel(
    status: BleServiceDiscoveryStatus,
    message: String,
    services: List<BleGattService>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "GATT Services (${services.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Discovery: ${status.name}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
        )

        if (services.isEmpty()) {
            Text(
                text = "No GATT services discovered.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(ServiceListHeight),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = services,
                key = { index, service -> service.serviceKey(index) },
            ) { _, service ->
                ServiceItem(service = service)
            }
        }
    }
}

@Composable
private fun ServiceItem(service: BleGattService) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = service.uuid,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Characteristics: ${service.characteristics.size}",
                style = MaterialTheme.typography.bodySmall,
            )
            service.characteristics.forEach { characteristic ->
                CharacteristicItem(characteristic = characteristic)
            }
        }
    }
}

@Composable
private fun CharacteristicItem(characteristic: BleGattCharacteristicInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = characteristic.uuid,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "Properties: ${characteristic.propertyLabels().joinToString()}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LogHeader(
    hasLogs: Boolean,
    onClearLogs: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "BLE Event Log",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(
            onClick = onClearLogs,
            enabled = hasLogs,
        ) {
            Text("Clear Logs")
        }
    }
}

@Composable
private fun LogList(
    logs: List<BleLogEntry>,
    modifier: Modifier = Modifier,
) {
    if (logs.isEmpty()) {
        Text(
            text = "No BLE events logged.",
            modifier = modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .height(LogListHeight),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(
            items = logs,
            key = { index, entry -> entry.logKey(index) },
        ) { _, entry ->
            Text(
                text = entry.formatForDisplay(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun BleLogEntry.logKey(index: Int): String {
    return listOf(
        timestampMillis,
        callbackName,
        gattStatus,
        connectionState,
        operationType,
        targetDevice,
        characteristicUuid,
        index,
    ).joinToString(separator = ":")
}

private fun BleGattService.serviceKey(index: Int): String = "$uuid:$index"

@Preview(showBackground = true)
@Composable
private fun BleScanScreenPreview() {
    TvAppTheme {
        BleScanScreen(
            uiState = BleScanUiState(
                status = BleScanStatus.Scanning,
                devices = listOf(
                    DiscoveredBleDevice(
                        name = "TV BLE",
                        address = "00:11:22:33:44:55",
                        rssi = -61,
                        lastSeenMillis = 0L,
                    ),
                ),
                logs = listOf(
                    BleLogEntry(
                        timestampMillis = 0L,
                        threadName = "main",
                        callbackName = "ScanCallback.onScanResult",
                        gattStatus = "N/A",
                        connectionState = "scanning",
                        operationType = "scan",
                        targetDevice = "00:11:22:33:44:55",
                        characteristicUuid = "N/A",
                        message = "callbackType=1 rssi=-61",
                    ),
                ),
                message = "Scanning for BLE devices.",
                selectedDevice = DiscoveredBleDevice(
                    name = "TV BLE",
                    address = "00:11:22:33:44:55",
                    rssi = -61,
                    lastSeenMillis = 0L,
                ),
                connectionStatus = BleConnectionStatus.Connected,
                connectionMessage = "Connected to TV BLE.",
                serviceDiscoveryStatus = BleServiceDiscoveryStatus.Discovered,
                serviceDiscoveryMessage = "Discovered 1 services and 1 characteristics.",
                services = listOf(
                    BleGattService(
                        uuid = "0000180f-0000-1000-8000-00805f9b34fb",
                        characteristics = listOf(
                            BleGattCharacteristicInfo(
                                uuid = "00002a19-0000-1000-8000-00805f9b34fb",
                                properties = BluetoothGattCharacteristic.PROPERTY_READ or
                                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                            ),
                        ),
                    ),
                ),
            ),
            onStartScan = {},
            onStopScan = {},
            onConnectDevice = {},
            onDisconnectDevice = {},
            onDeviceNameFilterChange = {},
            onClearLogs = {},
            onRequestPermissions = {},
        )
    }
}

private fun BleConnectionStatus.canStartConnect(): Boolean {
    return when (this) {
        BleConnectionStatus.Connecting,
        BleConnectionStatus.Connected,
        BleConnectionStatus.Disconnecting,
        -> false

        BleConnectionStatus.Disconnected,
        BleConnectionStatus.Failed,
        -> true
    }
}

private fun BleConnectionStatus.canDisconnect(isSelected: Boolean): Boolean {
    return isSelected &&
        (this == BleConnectionStatus.Connecting || this == BleConnectionStatus.Connected)
}
