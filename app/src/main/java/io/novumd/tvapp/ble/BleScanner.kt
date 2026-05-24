package io.novumd.tvapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context

class BleScanner(
    private val context: Context,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)
    private var scanCallback: ScanCallback? = null

    fun isScanning(): Boolean = scanCallback != null

    fun isBluetoothEnabled(): Boolean {
        return try {
            bluetoothManager?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(
        onDeviceFound: (DiscoveredBleDevice) -> Unit,
        onLog: (BleLogEntry) -> Unit,
        onScanFailed: (Int) -> Unit,
    ): BleScanStartResult {
        val missingPermissions = context.missingBleScanPermissions()
        if (missingPermissions.isNotEmpty()) {
            return BleScanStartResult.PermissionMissing(missingPermissions)
        }

        val adapter = bluetoothManager?.adapter
        if (adapter?.isEnabled != true) {
            return BleScanStartResult.BluetoothOff
        }

        val scanner = adapter.bluetoothLeScanner ?: return BleScanStartResult.ScannerUnavailable
        if (scanCallback != null) {
            return BleScanStartResult.Started
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.toDiscoveredBleDevice()
                onDeviceFound(device)
                onLog(
                    scanLog(
                        callbackName = "ScanCallback.onScanResult",
                        targetDevice = device.address,
                        message = "callbackType=$callbackType rssi=${result.rssi}",
                    ),
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    val device = result.toDiscoveredBleDevice()
                    onDeviceFound(device)
                    onLog(
                        scanLog(
                            callbackName = "ScanCallback.onBatchScanResults",
                            targetDevice = device.address,
                            message = "rssi=${result.rssi}",
                        ),
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scanCallback = null
                onLog(
                    scanLog(
                        callbackName = "ScanCallback.onScanFailed",
                        gattStatus = "scanError=$errorCode",
                        connectionState = "error",
                        targetDevice = "none",
                        message = "BLE scan failed",
                    ),
                )
                onScanFailed(errorCode)
            }
        }

        return try {
            scanner.startScan(callback)
            scanCallback = callback
            BleScanStartResult.Started
        } catch (_: SecurityException) {
            BleScanStartResult.PermissionMissing(context.missingBleScanPermissions())
        } catch (exception: IllegalStateException) {
            BleScanStartResult.Error(exception.message ?: "Unable to start BLE scan")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan(onLog: (BleLogEntry) -> Unit) {
        val callback = scanCallback ?: return
        scanCallback = null

        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
            onLog(
                scanLog(
                    callbackName = "BluetoothLeScanner.stopScan",
                    connectionState = "stopped",
                    targetDevice = "none",
                    message = "BLE scan stopped",
                ),
            )
        } catch (exception: SecurityException) {
            onLog(
                scanLog(
                    callbackName = "BluetoothLeScanner.stopScan",
                    gattStatus = "permissionDenied",
                    connectionState = "error",
                    targetDevice = "none",
                    message = exception.message ?: "Missing permission while stopping BLE scan",
                ),
            )
        }
    }

    private fun ScanResult.toDiscoveredBleDevice(): DiscoveredBleDevice {
        val deviceName = scanRecord?.deviceName ?: device.name ?: "Unknown device"
        return DiscoveredBleDevice(
            name = deviceName,
            address = device.address ?: "Unknown address",
            rssi = rssi,
            lastSeenMillis = now(),
        )
    }

    private fun scanLog(
        callbackName: String,
        gattStatus: String = "N/A",
        connectionState: String = "scanning",
        targetDevice: String,
        message: String,
    ): BleLogEntry {
        return BleLogEntry(
            timestampMillis = now(),
            threadName = Thread.currentThread().name,
            callbackName = callbackName,
            gattStatus = gattStatus,
            connectionState = connectionState,
            operationType = "scan",
            targetDevice = targetDevice,
            characteristicUuid = "N/A",
            message = message,
        )
    }
}

sealed interface BleScanStartResult {
    data object Started : BleScanStartResult
    data object BluetoothOff : BleScanStartResult
    data object ScannerUnavailable : BleScanStartResult
    data class PermissionMissing(val missingPermissions: List<String>) : BleScanStartResult
    data class Error(val message: String) : BleScanStartResult
}
