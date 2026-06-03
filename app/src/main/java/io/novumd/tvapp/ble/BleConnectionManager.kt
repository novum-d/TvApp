package io.novumd.tvapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

private const val CONNECT_TIMEOUT_MILLIS = 15_000L
private const val DISCONNECT_TIMEOUT_MILLIS = 5_000L
private const val SERVICE_DISCOVERY_TIMEOUT_MILLIS = 10_000L
private const val SUBSCRIPTION_TIMEOUT_MILLIS = 10_000L
private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@Suppress("DEPRECATION", "LongMethod", "ReturnCount", "TooManyFunctions")
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
    private var serviceDiscoveryTimeout: Runnable? = null
    private var subscriptionTimeout: Runnable? = null
    private var pendingSubscription: PendingSubscription? = null
    private var activeSubscription: BleCharacteristicSubscription? = null

    @SuppressLint("MissingPermission")
    fun connect(
        device: DiscoveredBleDevice,
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onServicesChanged: (BleServiceDiscoveryStatus, List<BleGattService>, String) -> Unit,
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onNotificationReceived: (BleNotificationEvent) -> Unit,
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

        val callback = connectionCallback(
            onStateChanged,
            onServicesChanged,
            onSubscriptionChanged,
            onNotificationReceived,
            onLog,
        )
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
    fun subscribeToCharacteristic(
        subscription: BleCharacteristicSubscription,
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ): BleSubscriptionStartResult {
        val missingPermissions = context.missingBleConnectPermissions()
        if (missingPermissions.isNotEmpty()) {
            return BleSubscriptionStartResult.PermissionMissing(missingPermissions)
        }

        if (pendingSubscription != null) {
            return BleSubscriptionStartResult.OperationActive
        }

        val gatt = bluetoothGatt ?: return BleSubscriptionStartResult.NoActiveGatt
        if (status != BleConnectionStatus.Connected) {
            return BleSubscriptionStartResult.NotConnected(status)
        }

        val target = findGattTarget(gatt, subscription.serviceUuid, subscription.characteristicUuid)
            ?: return BleSubscriptionStartResult.CharacteristicUnavailable
        if (!target.characteristic.supports(subscription.mode)) {
            return BleSubscriptionStartResult.UnsupportedProperty
        }

        val descriptor = target.characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            ?: return BleSubscriptionStartResult.CccdUnavailable

        val pending = PendingSubscription(
            subscription = subscription,
            enable = true,
            characteristic = target.characteristic,
            descriptor = descriptor,
        )
        pendingSubscription = pending
        onSubscriptionChanged(
            BleSubscriptionStatus.Subscribing,
            subscription,
            "Subscribing ${subscription.mode.name.lowercase()} for ${subscription.characteristicUuid}.",
        )
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothGatt.setCharacteristicNotification",
                connectionState = status.name,
                operationType = "subscribe",
                targetDevice = activeDevice?.address ?: gatt.device.address,
                characteristicUuid = subscription.characteristicUuid,
                message = "enable local notification requested mode=${subscription.mode.name}",
            ),
        )

        return tryStartSubscriptionWrite(
            gatt = gatt,
            pending = pending,
            descriptorValue = subscription.mode.enableDescriptorValue(),
            startFailureSubscription = null,
            onSubscriptionChanged = onSubscriptionChanged,
            onLog = onLog,
        )
    }

    @SuppressLint("MissingPermission")
    fun unsubscribeFromActiveCharacteristic(
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ): BleSubscriptionStartResult {
        val missingPermissions = context.missingBleConnectPermissions()
        if (missingPermissions.isNotEmpty()) {
            return BleSubscriptionStartResult.PermissionMissing(missingPermissions)
        }

        if (pendingSubscription != null) {
            return BleSubscriptionStartResult.OperationActive
        }

        val subscription = activeSubscription ?: return BleSubscriptionStartResult.NoActiveSubscription
        val gatt = bluetoothGatt ?: return BleSubscriptionStartResult.NoActiveGatt
        if (status != BleConnectionStatus.Connected) {
            return BleSubscriptionStartResult.NotConnected(status)
        }

        val target = findGattTarget(gatt, subscription.serviceUuid, subscription.characteristicUuid)
            ?: return BleSubscriptionStartResult.CharacteristicUnavailable
        val descriptor = target.characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            ?: return BleSubscriptionStartResult.CccdUnavailable
        val pending = PendingSubscription(
            subscription = subscription,
            enable = false,
            characteristic = target.characteristic,
            descriptor = descriptor,
        )
        pendingSubscription = pending
        onSubscriptionChanged(
            BleSubscriptionStatus.Unsubscribing,
            subscription,
            "Unsubscribing from ${subscription.characteristicUuid}.",
        )

        return tryStartSubscriptionWrite(
            gatt = gatt,
            pending = pending,
            descriptorValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
            startFailureSubscription = activeSubscription,
            onSubscriptionChanged = onSubscriptionChanged,
            onLog = onLog,
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect(
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        if (pendingSubscription != null) {
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BleConnectionManager.disconnect",
                    gattStatus = "operationActive",
                    connectionState = status.name,
                    operationType = "disconnect",
                    targetDevice = activeDevice?.address ?: "unknown",
                    message = "disconnect blocked because a subscription operation is active",
                ),
            )
            return
        }

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
        onServicesChanged: (BleServiceDiscoveryStatus, List<BleGattService>, String) -> Unit,
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onNotificationReceived: (BleNotificationEvent) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ): BluetoothGattCallback = object : BluetoothGattCallback() {
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
                    startServiceDiscovery(gatt, onServicesChanged, onLog)
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
                    onServicesChanged(
                        BleServiceDiscoveryStatus.Idle,
                        emptyList(),
                        "No discovered services.",
                    )
                    clearSubscriptionState(
                        onSubscriptionChanged = onSubscriptionChanged,
                        message = "No active notification subscription.",
                    )
                    updateStatus(
                        status = nextStatus,
                        message = message,
                        onStateChanged = onStateChanged,
                    )
                }

                gattStatus != BluetoothGatt.GATT_SUCCESS -> {
                    cancelTimeouts()
                    closeGatt(gatt, onLog, "closedAfterGattError")
                    onServicesChanged(
                        BleServiceDiscoveryStatus.Failed,
                        emptyList(),
                        "GATT callback failed with status $gattStatus.",
                    )
                    clearSubscriptionState(
                        onSubscriptionChanged = onSubscriptionChanged,
                        message = "Notification subscription cleared after GATT failure.",
                    )
                    updateStatus(
                        status = BleConnectionStatus.Failed,
                        message = "GATT callback failed with status $gattStatus.",
                        onStateChanged = onStateChanged,
                    )
                }
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            gattStatus: Int,
        ) {
            serviceDiscoveryTimeout?.let(handler::removeCallbacks)
            serviceDiscoveryTimeout = null

            val services = if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                gatt.services.map { service -> service.toBleGattService() }
            } else {
                emptyList()
            }
            val characteristicCount = services.sumOf { service -> service.characteristics.size }
            val message = if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                "serviceCount=${services.size} characteristicCount=$characteristicCount"
            } else {
                "service discovery failed with status $gattStatus"
            }
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BluetoothGattCallback.onServicesDiscovered",
                    gattStatus = gattStatus.toString(),
                    connectionState = status.name,
                    operationType = "serviceDiscovery",
                    targetDevice = activeDevice?.address ?: gatt.device.address,
                    message = message,
                ),
            )

            if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                onServicesChanged(
                    BleServiceDiscoveryStatus.Discovered,
                    services,
                    "Discovered ${services.size} services and $characteristicCount characteristics.",
                )
            } else {
                onServicesChanged(
                    BleServiceDiscoveryStatus.Failed,
                    emptyList(),
                    "Service discovery failed with status $gattStatus.",
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            gattStatus: Int,
        ) {
            val pending = pendingSubscription
            if (
                pending == null ||
                descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID ||
                descriptor.characteristic.uuid.toString() != pending.subscription.characteristicUuid
            ) {
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BluetoothGattCallback.onDescriptorWrite",
                        gattStatus = gattStatus.toString(),
                        connectionState = status.name,
                        operationType = "subscribe",
                        targetDevice = activeDevice?.address ?: gatt.device.address,
                        characteristicUuid = descriptor.characteristic.uuid.toString(),
                        message = "ignored descriptor write callback",
                    ),
                )
                return
            }

            subscriptionTimeout?.let(handler::removeCallbacks)
            subscriptionTimeout = null
            pendingSubscription = null

            val success = gattStatus == BluetoothGatt.GATT_SUCCESS
            val nextStatus = when {
                success && pending.enable -> BleSubscriptionStatus.Subscribed
                success -> BleSubscriptionStatus.Idle
                else -> BleSubscriptionStatus.Failed
            }
            val nextSubscription = when {
                success && pending.enable -> pending.subscription
                success -> null
                pending.enable -> null
                else -> activeSubscription
            }
            if (success) {
                activeSubscription = nextSubscription
            } else if (pending.enable) {
                setCharacteristicNotification(
                    gatt = gatt,
                    characteristic = pending.characteristic,
                    enable = false,
                    onLog = onLog,
                )
            }
            val message = descriptorWriteMessage(pending, gattStatus)

            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BluetoothGattCallback.onDescriptorWrite",
                    gattStatus = gattStatus.toString(),
                    connectionState = status.name,
                    operationType = if (pending.enable) "subscribe" else "unsubscribe",
                    targetDevice = activeDevice?.address ?: gatt.device.address,
                    characteristicUuid = pending.subscription.characteristicUuid,
                    message = message,
                ),
            )
            onSubscriptionChanged(nextStatus, nextSubscription, message)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val timestampMillis = now()
            val serviceUuid = characteristic.service?.uuid?.toString() ?: "unknown"
            val characteristicUuid = characteristic.uuid.toString()
            val targetDevice = activeDevice?.address ?: gatt.device.address
            val valueHex = value.toDisplayHex()
            onLog(
                connectionLog(
                    timestampMillis = timestampMillis,
                    callbackName = "BluetoothGattCallback.onCharacteristicChanged",
                    connectionState = status.name,
                    operationType = "notification",
                    targetDevice = targetDevice,
                    characteristicUuid = characteristicUuid,
                    message = "service=$serviceUuid bytes=${value.size} value=$valueHex",
                ),
            )
            onNotificationReceived(
                BleNotificationEvent(
                    timestampMillis = timestampMillis,
                    targetDevice = targetDevice,
                    serviceUuid = serviceUuid,
                    characteristicUuid = characteristicUuid,
                    valueHex = valueHex,
                    byteCount = value.size,
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(
        gatt: BluetoothGatt,
        onServicesChanged: (BleServiceDiscoveryStatus, List<BleGattService>, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        onServicesChanged(
            BleServiceDiscoveryStatus.Discovering,
            emptyList(),
            "Discovering GATT services.",
        )
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothGatt.discoverServices",
                connectionState = status.name,
                operationType = "serviceDiscovery",
                targetDevice = activeDevice?.address ?: gatt.device.address,
                message = "discoverServices requested",
            ),
        )

        val started = try {
            gatt.discoverServices()
        } catch (exception: SecurityException) {
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BluetoothGatt.discoverServices",
                    gattStatus = "permissionDenied",
                    connectionState = "failed",
                    operationType = "serviceDiscovery",
                    targetDevice = activeDevice?.address ?: gatt.device.address,
                    message = exception.message ?: "Missing permission while discovering services",
                ),
            )
            false
        } catch (exception: RuntimeException) {
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BluetoothGatt.discoverServices",
                    gattStatus = "discoverServicesError",
                    connectionState = "failed",
                    operationType = "serviceDiscovery",
                    targetDevice = activeDevice?.address ?: gatt.device.address,
                    message = exception.message ?: "Unable to discover services",
                ),
            )
            false
        }

        if (started) {
            scheduleServiceDiscoveryTimeout(gatt, onServicesChanged, onLog)
        } else {
            onServicesChanged(
                BleServiceDiscoveryStatus.Failed,
                emptyList(),
                "Service discovery could not be started.",
            )
        }
    }

    private fun scheduleServiceDiscoveryTimeout(
        gatt: BluetoothGatt,
        onServicesChanged: (BleServiceDiscoveryStatus, List<BleGattService>, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        serviceDiscoveryTimeout?.let(handler::removeCallbacks)
        serviceDiscoveryTimeout = null
        val timeout = Runnable {
            if (status == BleConnectionStatus.Connected) {
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BleConnectionManager.serviceDiscoveryTimeout",
                        gattStatus = "timeout",
                        connectionState = status.name,
                        operationType = "serviceDiscovery",
                        targetDevice = activeDevice?.address ?: gatt.device.address,
                        message = "service discovery timeout after ${SERVICE_DISCOVERY_TIMEOUT_MILLIS}ms",
                    ),
                )
                onServicesChanged(
                    BleServiceDiscoveryStatus.Failed,
                    emptyList(),
                    "Service discovery timed out.",
                )
            }
        }
        serviceDiscoveryTimeout = timeout
        handler.postDelayed(timeout, SERVICE_DISCOVERY_TIMEOUT_MILLIS)
    }

    @SuppressLint("MissingPermission")
    private fun tryStartSubscriptionWrite(
        gatt: BluetoothGatt,
        pending: PendingSubscription,
        descriptorValue: ByteArray,
        startFailureSubscription: BleCharacteristicSubscription?,
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ): BleSubscriptionStartResult {
        val subscription = pending.subscription
        val notificationStarted = setCharacteristicNotification(
            gatt = gatt,
            characteristic = pending.characteristic,
            enable = pending.enable,
            onLog = onLog,
        )
        if (!notificationStarted) {
            pendingSubscription = null
            onSubscriptionChanged(
                BleSubscriptionStatus.Failed,
                startFailureSubscription,
                "setCharacteristicNotification returned false.",
            )
            return BleSubscriptionStartResult.LocalNotificationFailed
        }

        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothGatt.writeDescriptor",
                connectionState = status.name,
                operationType = if (pending.enable) "subscribe" else "unsubscribe",
                targetDevice = activeDevice?.address ?: gatt.device.address,
                characteristicUuid = subscription.characteristicUuid,
                message = "CCCD write requested mode=${subscription.mode.name} enable=${pending.enable}",
            ),
        )

        return try {
            val started = writeDescriptor(gatt, pending.descriptor, descriptorValue)
            if (started) {
                scheduleSubscriptionTimeout(
                    gatt = gatt,
                    pending = pending,
                    onSubscriptionChanged = onSubscriptionChanged,
                    onLog = onLog,
                )
                BleSubscriptionStartResult.Started
            } else {
                pendingSubscription = null
                if (pending.enable) {
                    setCharacteristicNotification(
                        gatt = gatt,
                        characteristic = pending.characteristic,
                        enable = false,
                        onLog = onLog,
                    )
                }
                onSubscriptionChanged(
                    BleSubscriptionStatus.Failed,
                    startFailureSubscription,
                    "CCCD write could not be started.",
                )
                BleSubscriptionStartResult.DescriptorWriteNotStarted
            }
        } catch (exception: SecurityException) {
            failSubscriptionStart(
                gatt = gatt,
                pending = pending,
                gattStatus = "permissionDenied",
                message = exception.message ?: "Missing permission while writing CCCD",
                onSubscriptionChanged = onSubscriptionChanged,
                onLog = onLog,
            )
            BleSubscriptionStartResult.PermissionMissing(context.missingBleConnectPermissions())
        } catch (exception: RuntimeException) {
            failSubscriptionStart(
                gatt = gatt,
                pending = pending,
                gattStatus = "descriptorWriteError",
                message = exception.message ?: "Unable to write CCCD",
                onSubscriptionChanged = onSubscriptionChanged,
                onLog = onLog,
            )
            BleSubscriptionStartResult.Error(exception.message ?: "Unable to write CCCD")
        }
    }

    private fun scheduleSubscriptionTimeout(
        gatt: BluetoothGatt,
        pending: PendingSubscription,
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        subscriptionTimeout?.let(handler::removeCallbacks)
        subscriptionTimeout = null
        val timeout = Runnable {
            if (pendingSubscription == pending) {
                pendingSubscription = null
                if (pending.enable) {
                    setCharacteristicNotification(
                        gatt = gatt,
                        characteristic = pending.characteristic,
                        enable = false,
                        onLog = onLog,
                    )
                }
                val nextSubscription = if (pending.enable) null else activeSubscription
                val message = "CCCD write timeout after ${SUBSCRIPTION_TIMEOUT_MILLIS}ms."
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BleConnectionManager.subscriptionTimeout",
                        gattStatus = "timeout",
                        connectionState = status.name,
                        operationType = if (pending.enable) "subscribe" else "unsubscribe",
                        targetDevice = activeDevice?.address ?: gatt.device.address,
                        characteristicUuid = pending.subscription.characteristicUuid,
                        message = message,
                    ),
                )
                onSubscriptionChanged(BleSubscriptionStatus.Failed, nextSubscription, message)
            }
        }
        subscriptionTimeout = timeout
        handler.postDelayed(timeout, SUBSCRIPTION_TIMEOUT_MILLIS)
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
        serviceDiscoveryTimeout?.let(handler::removeCallbacks)
        serviceDiscoveryTimeout = null
        subscriptionTimeout?.let(handler::removeCallbacks)
        subscriptionTimeout = null
        pendingSubscription = null
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
        } catch (exception: SecurityException) {
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BluetoothGatt.close",
                    gattStatus = "permissionDenied",
                    connectionState = "error",
                    operationType = "disconnect",
                    targetDevice = activeDevice?.address ?: "unknown",
                    message = exception.message ?: "Missing permission while closing GATT resource",
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
            activeSubscription = null
        }
    }

    private fun clearSubscriptionState(
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        message: String,
    ) {
        subscriptionTimeout?.let(handler::removeCallbacks)
        subscriptionTimeout = null
        pendingSubscription = null
        activeSubscription = null
        onSubscriptionChanged(BleSubscriptionStatus.Idle, null, message)
    }

    private fun failSubscriptionStart(
        gatt: BluetoothGatt,
        pending: PendingSubscription,
        gattStatus: String,
        message: String,
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        pendingSubscription = null
        if (pending.enable) {
            setCharacteristicNotification(
                gatt = gatt,
                characteristic = pending.characteristic,
                enable = false,
                onLog = onLog,
            )
        }
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothGatt.writeDescriptor",
                gattStatus = gattStatus,
                connectionState = status.name,
                operationType = if (pending.enable) "subscribe" else "unsubscribe",
                targetDevice = activeDevice?.address ?: gatt.device.address,
                characteristicUuid = pending.subscription.characteristicUuid,
                message = message,
            ),
        )
        onSubscriptionChanged(
            BleSubscriptionStatus.Failed,
            if (pending.enable) null else activeSubscription,
            message,
        )
    }
}

enum class BleConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
    Failed,
}

enum class BleServiceDiscoveryStatus {
    Idle,
    Discovering,
    Discovered,
    Failed,
}

enum class BleSubscriptionStatus {
    Idle,
    Subscribing,
    Subscribed,
    Unsubscribing,
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

sealed interface BleSubscriptionStartResult {
    data object Started : BleSubscriptionStartResult
    data object NoActiveGatt : BleSubscriptionStartResult
    data object NoActiveSubscription : BleSubscriptionStartResult
    data class NotConnected(val status: BleConnectionStatus) : BleSubscriptionStartResult
    data object OperationActive : BleSubscriptionStartResult
    data object CharacteristicUnavailable : BleSubscriptionStartResult
    data object UnsupportedProperty : BleSubscriptionStartResult
    data object CccdUnavailable : BleSubscriptionStartResult
    data object LocalNotificationFailed : BleSubscriptionStartResult
    data object DescriptorWriteNotStarted : BleSubscriptionStartResult
    data class PermissionMissing(val missingPermissions: List<String>) : BleSubscriptionStartResult
    data class Error(val message: String) : BleSubscriptionStartResult
}

private data class PendingSubscription(
    val subscription: BleCharacteristicSubscription,
    val enable: Boolean,
    val characteristic: BluetoothGattCharacteristic,
    val descriptor: BluetoothGattDescriptor,
)

private data class GattTarget(
    val characteristic: BluetoothGattCharacteristic,
)

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
    characteristicUuid: String = "N/A",
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
        characteristicUuid = characteristicUuid,
        message = message,
    )
}

private fun findGattTarget(
    gatt: BluetoothGatt,
    serviceUuidText: String,
    characteristicUuidText: String,
): GattTarget? {
    val serviceUuid = parseUuid(serviceUuidText) ?: return null
    val characteristicUuid = parseUuid(characteristicUuidText) ?: return null
    val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid) ?: return null
    return GattTarget(characteristic = characteristic)
}

private fun parseUuid(uuid: String): UUID? {
    return try {
        UUID.fromString(uuid)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun BluetoothGattCharacteristic.supports(mode: BleSubscriptionMode): Boolean {
    return when (mode) {
        BleSubscriptionMode.Notification -> properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        BleSubscriptionMode.Indication -> properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    }
}

@Suppress("DEPRECATION")
private fun BleSubscriptionMode.enableDescriptorValue(): ByteArray {
    return when (this) {
        BleSubscriptionMode.Notification -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        BleSubscriptionMode.Indication -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
    }
}

@SuppressLint("MissingPermission")
private fun setCharacteristicNotification(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    enable: Boolean,
    onLog: (BleLogEntry) -> Unit,
): Boolean {
    val started = gatt.setCharacteristicNotification(characteristic, enable)
    onLog(
        connectionLog(
            timestampMillis = System.currentTimeMillis(),
            callbackName = "BluetoothGatt.setCharacteristicNotification",
            gattStatus = started.toString(),
            connectionState = "local",
            operationType = if (enable) "subscribe" else "unsubscribe",
            targetDevice = gatt.device.address,
            characteristicUuid = characteristic.uuid.toString(),
            message = "local notification enable=$enable",
        ),
    )
    return started
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun writeDescriptor(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    value: ByteArray,
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        descriptor.value = value
        gatt.writeDescriptor(descriptor)
    }
}

private fun descriptorWriteMessage(
    pending: PendingSubscription,
    gattStatus: Int,
): String {
    val direction = if (pending.enable) "subscribe" else "unsubscribe"
    return if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
        "$direction CCCD write succeeded mode=${pending.subscription.mode.name}"
    } else {
        "$direction CCCD write failed mode=${pending.subscription.mode.name} status=$gattStatus"
    }
}
