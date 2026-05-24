package io.novumd.tvapp.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.novumd.tvapp.ble.BleLogEntry
import io.novumd.tvapp.ble.DiscoveredBleDevice
import io.novumd.tvapp.ble.formatForDisplay
import io.novumd.tvapp.ui.theme.TvAppTheme

@Composable
fun BleScanScreen(
    uiState: BleScanUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScanHeader(
            uiState = uiState,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onRequestPermissions = onRequestPermissions,
        )

        HorizontalDivider()

        Text(
            text = "Detected Devices (${uiState.devices.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        DeviceList(
            devices = uiState.devices,
            modifier = Modifier.weight(1f),
        )

        HorizontalDivider()

        Text(
            text = "BLE Event Log",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LogList(
            logs = uiState.logs,
            modifier = Modifier.weight(1f),
        )
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
    modifier: Modifier = Modifier,
) {
    if (devices.isEmpty()) {
        Text(
            text = "No BLE devices detected.",
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = devices,
            key = { it.address },
        ) { device ->
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
                }
            }
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
            text = "No scan events logged.",
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(logs) { entry ->
            Text(
                text = entry.formatForDisplay(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

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
            ),
            onStartScan = {},
            onStopScan = {},
            onRequestPermissions = {},
        )
    }
}
