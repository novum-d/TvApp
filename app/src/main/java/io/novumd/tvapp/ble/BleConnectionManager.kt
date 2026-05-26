package io.novumd.tvapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper

private const val CONNECT_TIMEOUT_MILLIS = 15_000L
private const val DISCONNECT_TIMEOUT_MILLIS = 5_000L

class BleConnectionManager(
    private val context: Context,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)
    private var bluetoothGatt: BluetoothGatt? = null
    private var activeDevice: DiscoveredBleDevice? = null
    private var status: BleConnectionStatus = BleConnectionStatus.Disconnected
    private var connectTimeout: Runnable? = null
    private var disconnectTimeout: Runnable? = null

    @SuppressLint("MissingPermission")
    fun connect(
        device: DiscoveredBleDevice,
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ): BleConnectionStartResult {
        val missingPermissions = context.missingBleConnectPermissions()
        if (missingPermissions.isNotEmpty()) {
            return BleConnectionStartResult.PermissionMissing(missingPermissions)
        }

        val adapter = bluetoothManager?.adapter
        if (adapter?.isEnabled != true) {
            return BleConnectionStartResult.BluetoothOff
        }

        if (
            status == BleConnectionStatus.Connecting ||
            status == BleConnectionStatus.Connected ||
            status == BleConnectionStatus.Disconnecting
        ) {
            return BleConnectionStartResult.ConnectionActive(status)
        }

        val bluetoothDevice = try {
            adapter.getRemoteDevice(device.address)
        } catch (_: IllegalArgumentException) {
            return BleConnectionStartResult.InvalidAddress
        }

        activeDevice = device
        updateStatus(
            status = BleConnectionStatus.Connecting,
            message = "Connecting to ${device.name}.",
            onStateChanged = onStateChanged,
        )
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothDevice.connectGatt",
                connectionState = "connecting",
                operationType = "connect",
                targetDevice = device.address,
                message = "connectGatt requested",
            ),
        )

        val callback = connectionCallback(onStateChanged, onLog)
        return try {
            bluetoothGatt = bluetoothDevice.connectGatt(
                context,
                false,
                callback,
                android.bluetooth.BluetoothDevice.TRANSPORT_LE,
            )
            scheduleConnectTimeout(onStateChanged, onLog)
            BleConnectionStartResult.Started
        } catch (exception: SecurityException) {
            failConnect(
                gattStatus = "permissionDenied",
                message = exception.message ?: "Missing permission while connecting GATT",
                onStateChanged = onStateChanged,
                onLog = onLog,
            )
            BleConnectionStartResult.PermissionMissing(context.missingBleConnectPermissions())
        } catch (exception: RuntimeException) {
            failConnect(
                gattStatus = "connectGattError",
                message = exception.message ?: "Unable to connect GATT",
                onStateChanged = onStateChanged,
                onLog = onLog,
            )
            BleConnectionStartResult.Error(exception.message ?: "Unable to connect GATT")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        val gatt = bluetoothGatt
        val device = activeDevice
        if (gatt == null || device == null) {
            updateStatus(
                status = BleConnectionStatus.Disconnected,
                message = "No active GATT connection.",
                onStateChanged = onStateChanged,
            )
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BleConnectionManager.disconnect",
                    gattStatus = "noActiveGatt",
                    connectionState = "disconnected",
                    operationType = "disconnect",
                    targetDevice = "none",
                    message = "No active GATT to disconnect",
                ),
            )
            return
        }

        updateStatus(
            status = BleConnectionStatus.Disconnecting,
            message = "Disconnecting from ${device.name}.",
            onStateChanged = onStateChanged,
        )
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothGatt.disconnect",
                connectionState = "disconnecting",
                operationType = "disconnect",
                targetDevice = device.address,
                message = "disconnect requested",
            ),
        )

        try {
            gatt.disconnect()
            scheduleDisconnectTimeout(gatt, onStateChanged, onLog)
        } catch (exception: SecurityException) {
            closeGatt(gatt, onLog, "permissionDenied")
            updateStatus(
                status = BleConnectionStatus.Failed,
                message = exception.message ?: "Missing permission while disconnecting GATT",
                onStateChanged = onStateChanged,
            )
        } catch (exception: RuntimeException) {
            closeGatt(gatt, onLog, "disconnectError")
            updateStatus(
                status = BleConnectionStatus.Failed,
                message = exception.message ?: "Unable to disconnect GATT",
                onStateChanged = onStateChanged,
            )
        }
    }

    fun close(onLog: (BleLogEntry) -> Unit) {
        cancelTimeouts()
        bluetoothGatt?.let { closeGatt(it, onLog, "closed") }
        bluetoothGatt = null
        activeDevice = null
        status = BleConnectionStatus.Disconnected
    }

    private fun connectionCallback(
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                gattStatus: Int,
                newState: Int,
            ) {
                val deviceAddress = activeDevice?.address ?: gatt.device.address
                val newStateName = newState.connectionStateName()
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BluetoothGattCallback.onConnectionStateChange",
                        gattStatus = gattStatus.toString(),
                        connectionState = newStateName,
                        operationType = connectionOperationType(status, newState),
                        targetDevice = deviceAddress,
                        message = "newState=$newStateName",
                    ),
                )

                when {
                    gattStatus == BluetoothGatt.GATT_SUCCESS &&
                        newState == BluetoothProfile.STATE_CONNECTED -> {
                        cancelTimeouts()
                        updateStatus(
                            status = BleConnectionStatus.Connected,
                            message = "Connected to ${activeDevice?.name ?: deviceAddress}.",
                            onStateChanged = onStateChanged,
                        )
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        cancelTimeouts()
                        val nextStatus = if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                            BleConnectionStatus.Disconnected
                        } else {
                            BleConnectionStatus.Failed
                        }
                        val message = if (nextStatus == BleConnectionStatus.Disconnected) {
                            "Disconnected from ${activeDevice?.name ?: deviceAddress}."
                        } else {
                            "GATT disconnected with status $gattStatus."
                        }
                        closeGatt(gatt, onLog, "closedAfterDisconnect")
                        updateStatus(
                            status = nextStatus,
                            message = message,
                            onStateChanged = onStateChanged,
                        )
                    }

                    gattStatus != BluetoothGatt.GATT_SUCCESS -> {
                        cancelTimeouts()
                        closeGatt(gatt, onLog, "closedAfterGattError")
                        updateStatus(
                            status = BleConnectionStatus.Failed,
                            message = "GATT callback failed with status $gattStatus.",
                            onStateChanged = onStateChanged,
                        )
                    }
                }
            }
        }
    }

    private fun scheduleConnectTimeout(
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        connectTimeout?.let(handler::removeCallbacks)
        connectTimeout = null
        val timeout = Runnable {
            if (status == BleConnectionStatus.Connecting) {
                val target = activeDevice?.address ?: "unknown"
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BleConnectionManager.connectTimeout",
                        gattStatus = "timeout",
                        connectionState = "failed",
                        operationType = "connect",
                        targetDevice = target,
                        message = "connect timeout after ${CONNECT_TIMEOUT_MILLIS}ms",
                    ),
                )
                bluetoothGatt?.let { closeGatt(it, onLog, "closedAfterConnectTimeout") }
                updateStatus(
                    status = BleConnectionStatus.Failed,
                    message = "Connect timed out.",
                    onStateChanged = onStateChanged,
                )
            }
        }
        connectTimeout = timeout
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MILLIS)
    }

    private fun scheduleDisconnectTimeout(
        gatt: BluetoothGatt,
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        disconnectTimeout?.let(handler::removeCallbacks)
        disconnectTimeout = null
        val timeout = Runnable {
            if (status == BleConnectionStatus.Disconnecting) {
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BleConnectionManager.disconnectTimeout",
                        gattStatus = "timeout",
                        connectionState = "disconnected",
                        operationType = "disconnect",
                        targetDevice = activeDevice?.address ?: "unknown",
                        message = "disconnect timeout after ${DISCONNECT_TIMEOUT_MILLIS}ms",
                    ),
                )
                closeGatt(gatt, onLog, "closedAfterDisconnectTimeout")
                updateStatus(
                    status = BleConnectionStatus.Disconnected,
                    message = "Disconnected after timeout.",
                    onStateChanged = onStateChanged,
                )
            }
        }
        disconnectTimeout = timeout
        handler.postDelayed(timeout, DISCONNECT_TIMEOUT_MILLIS)
    }

    private fun failConnect(
        gattStatus: String,
        message: String,
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        connectTimeout?.let(handler::removeCallbacks)
        connectTimeout = null
        val targetDevice = activeDevice?.address ?: "unknown"
        bluetoothGatt?.let { closeGatt(it, onLog, "closedAfterConnectFailure") }
        updateStatus(
            status = BleConnectionStatus.Failed,
            message = message,
            onStateChanged = onStateChanged,
        )
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothDevice.connectGatt",
                gattStatus = gattStatus,
                connectionState = "failed",
                operationType = "connect",
                targetDevice = targetDevice,
                message = message,
            ),
        )
    }

    private fun updateStatus(
        status: BleConnectionStatus,
        message: String,
        onStateChanged: (BleConnectionStatus, String) -> Unit,
    ) {
        this.status = status
        onStateChanged(status, message)
    }

    private fun cancelTimeouts() {
        connectTimeout?.let(handler::removeCallbacks)
        connectTimeout = null
        disconnectTimeout?.let(handler::removeCallbacks)
        disconnectTimeout = null
    }

    private fun closeGatt(
        gatt: BluetoothGatt,
        onLog: (BleLogEntry) -> Unit,
        gattStatus: String,
    ) {
        try {
            gatt.close()
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BluetoothGatt.close",
                    gattStatus = gattStatus,
                    connectionState = "closed",
                    operationType = "disconnect",
                    targetDevice = activeDevice?.address ?: "unknown",
                    message = "GATT resource closed",
                ),
            )
        } catch (exception: RuntimeException) {
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BluetoothGatt.close",
                    gattStatus = "closeError",
                    connectionState = "error",
                    operationType = "disconnect",
                    targetDevice = activeDevice?.address ?: "unknown",
                    message = exception.message ?: "Unable to close GATT resource",
                ),
            )
        } finally {
            if (bluetoothGatt == gatt) {
                bluetoothGatt = null
            }
            activeDevice = null
        }
    }
}

enum class BleConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
    Failed,
}

sealed interface BleConnectionStartResult {
    data object Started : BleConnectionStartResult
    data object BluetoothOff : BleConnectionStartResult
    data class ConnectionActive(val status: BleConnectionStatus) : BleConnectionStartResult
    data object InvalidAddress : BleConnectionStartResult
    data class PermissionMissing(val missingPermissions: List<String>) : BleConnectionStartResult
    data class Error(val message: String) : BleConnectionStartResult
}

private fun Int.connectionStateName(): String {
    return when (this) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "unknown($this)"
    }
}

private fun connectionOperationType(
    status: BleConnectionStatus,
    newState: Int,
): String {
    return if (
        status == BleConnectionStatus.Disconnecting ||
        newState == BluetoothProfile.STATE_DISCONNECTED
    ) {
        "disconnect"
    } else {
        "connect"
    }
}

private fun connectionLog(
    timestampMillis: Long,
    callbackName: String,
    gattStatus: String = BluetoothGatt.GATT_SUCCESS.toString(),
    connectionState: String,
    operationType: String,
    targetDevice: String,
    message: String,
): BleLogEntry {
    return BleLogEntry(
        timestampMillis = timestampMillis,
        threadName = Thread.currentThread().name,
        callbackName = callbackName,
        gattStatus = gattStatus,
        connectionState = connectionState,
        operationType = operationType,
        targetDevice = targetDevice,
        characteristicUuid = "N/A",
        message = message,
    )
}
