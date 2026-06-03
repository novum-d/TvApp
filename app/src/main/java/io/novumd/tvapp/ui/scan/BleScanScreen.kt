@file:Suppress("TooManyFunctions")

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
import io.novumd.tvapp.ble.BleCharacteristicSubscription
import io.novumd.tvapp.ble.BleConnectionStatus
import io.novumd.tvapp.ble.BleGattCharacteristicInfo
import io.novumd.tvapp.ble.BleGattService
import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.BleServiceDiscoveryStatus
import io.novumd.tvapp.ble.BleSubscriptionMode
import io.novumd.tvapp.ble.BleSubscriptionStatus
import io.novumd.tvapp.ble.DiscoveredBleDevice
import io.novumd.tvapp.ble.formatForDisplay
import io.novumd.tvapp.ble.propertyLabels
import io.novumd.tvapp.ble.subscriptionModes
import io.novumd.tvapp.ui.theme.TvAppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DeviceListHeight = 360.dp
private val ServiceListHeight = 280.dp
private val LogListHeight = 96.dp

data class BleScanScreenActions(
    val onStartScan: () -> Unit,
    val onStopScan: () -> Unit,
    val onConnectDevice: (DiscoveredBleDevice) -> Unit,
    val onDisconnectDevice: () -> Unit,
    val onSubscribe: (String, String, BleSubscriptionMode) -> Unit,
    val onUnsubscribe: () -> Unit,
    val onDeviceNameFilterChange: (String) -> Unit,
    val onClearLogs: () -> Unit,
    val onRequestPermissions: () -> Unit,
)

@Composable
fun BleScanScreen(
    uiState: BleScanUiState,
    actions: BleScanScreenActions,
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
                onStartScan = actions.onStartScan,
                onStopScan = actions.onStopScan,
                onRequestPermissions = actions.onRequestPermissions,
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
                onValueChange = actions.onDeviceNameFilterChange,
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
                subscriptionStatus = uiState.subscriptionStatus,
                onConnectDevice = actions.onConnectDevice,
                onDisconnectDevice = actions.onDisconnectDevice,
            )
        }

        item(key = "service_divider") {
            HorizontalDivider()
        }

        item(key = "service_panel") {
            ServiceDiscoveryPanel(
                uiState = uiState,
                onSubscribe = actions.onSubscribe,
                onUnsubscribe = actions.onUnsubscribe,
            )
        }

        item(key = "log_divider") {
            HorizontalDivider()
        }

        item(key = "log_header") {
            LogHeader(
                hasLogs = uiState.logs.isNotEmpty(),
                onClearLogs = actions.onClearLogs,
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
    subscriptionStatus: BleSubscriptionStatus,
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
                            enabled = connectionStatus.canDisconnect(isSelected) &&
                                subscriptionStatus.canRunGattOperation(),
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
    uiState: BleScanUiState,
    onSubscribe: (String, String, BleSubscriptionMode) -> Unit,
    onUnsubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "GATT Services (${uiState.services.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Discovery: ${uiState.serviceDiscoveryStatus.name}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = uiState.serviceDiscoveryMessage,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Subscription: ${uiState.subscriptionStatus.name}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = uiState.subscriptionMessage,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Last received: ${uiState.lastNotification?.timestampMillis?.formatLogTimestamp() ?: "none"}",
            style = MaterialTheme.typography.bodySmall,
        )
        uiState.lastNotification?.let { event ->
            Text(
                text = "${event.characteristicUuid} bytes=${event.byteCount} value=${event.valueHex}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (uiState.services.isEmpty()) {
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
                items = uiState.services,
                key = { index, service -> service.serviceKey(index) },
            ) { _, service ->
                ServiceItem(
                    service = service,
                    connectionStatus = uiState.connectionStatus,
                    subscriptionStatus = uiState.subscriptionStatus,
                    activeSubscription = uiState.activeSubscription,
                    onSubscribe = onSubscribe,
                    onUnsubscribe = onUnsubscribe,
                )
            }
        }
    }
}

@Composable
private fun ServiceItem(
    service: BleGattService,
    connectionStatus: BleConnectionStatus,
    subscriptionStatus: BleSubscriptionStatus,
    activeSubscription: BleCharacteristicSubscription?,
    onSubscribe: (String, String, BleSubscriptionMode) -> Unit,
    onUnsubscribe: () -> Unit,
) {
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
                CharacteristicItem(
                    serviceUuid = service.uuid,
                    characteristic = characteristic,
                    connectionStatus = connectionStatus,
                    subscriptionStatus = subscriptionStatus,
                    activeSubscription = activeSubscription,
                    onSubscribe = onSubscribe,
                    onUnsubscribe = onUnsubscribe,
                )
            }
        }
    }
}

@Composable
private fun CharacteristicItem(
    serviceUuid: String,
    characteristic: BleGattCharacteristicInfo,
    connectionStatus: BleConnectionStatus,
    subscriptionStatus: BleSubscriptionStatus,
    activeSubscription: BleCharacteristicSubscription?,
    onSubscribe: (String, String, BleSubscriptionMode) -> Unit,
    onUnsubscribe: () -> Unit,
) {
    val subscriptionModes = characteristic.subscriptionModes()
    val activeMode = activeSubscription?.takeIf {
        it.serviceUuid == serviceUuid && it.characteristicUuid == characteristic.uuid
    }?.mode
    val canStartSubscription = connectionStatus == BleConnectionStatus.Connected &&
        subscriptionStatus.canStartSubscription()
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
        if (subscriptionModes.isNotEmpty()) {
            Text(
                text = "Subscribe support: ${subscriptionModes.joinToString { it.name.lowercase() }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                subscriptionModes.forEach { mode ->
                    Button(
                        onClick = { onSubscribe(serviceUuid, characteristic.uuid, mode) },
                        enabled = canStartSubscription && activeSubscription == null,
                    ) {
                        Text(mode.actionLabel())
                    }
                }
                OutlinedButton(
                    onClick = onUnsubscribe,
                    enabled = activeMode != null && subscriptionStatus.canStopSubscription(),
                ) {
                    Text("Unsubscribe")
                }
            }
        }
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
            actions = BleScanScreenActions(
                onStartScan = {},
                onStopScan = {},
                onConnectDevice = {},
                onDisconnectDevice = {},
                onSubscribe = { _, _, _ -> },
                onUnsubscribe = {},
                onDeviceNameFilterChange = {},
                onClearLogs = {},
                onRequestPermissions = {},
            ),
        )
    }
}

private fun BleConnectionStatus.canStartConnect(): Boolean = when (this) {
    BleConnectionStatus.Connecting,
    BleConnectionStatus.Connected,
    BleConnectionStatus.Disconnecting,
    -> false

    BleConnectionStatus.Disconnected,
    BleConnectionStatus.Failed,
    -> true
}

private fun BleConnectionStatus.canDisconnect(isSelected: Boolean): Boolean = isSelected &&
    (this == BleConnectionStatus.Connecting || this == BleConnectionStatus.Connected)

private fun BleSubscriptionStatus.canStartSubscription(): Boolean =
    this == BleSubscriptionStatus.Idle || this == BleSubscriptionStatus.Failed

private fun BleSubscriptionStatus.canRunGattOperation(): Boolean =
    this != BleSubscriptionStatus.Subscribing && this != BleSubscriptionStatus.Unsubscribing

private fun BleSubscriptionStatus.canStopSubscription(): Boolean =
    this == BleSubscriptionStatus.Subscribed || this == BleSubscriptionStatus.Failed

private fun BleSubscriptionMode.actionLabel(): String = when (this) {
    BleSubscriptionMode.Notification -> "Notify"
    BleSubscriptionMode.Indication -> "Indicate"
}

private fun Long.formatLogTimestamp(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(this))
