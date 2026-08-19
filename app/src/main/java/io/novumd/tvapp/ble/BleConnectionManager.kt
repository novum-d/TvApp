@file:Suppress("TooManyFunctions")

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
private const val READ_TIMEOUT_MILLIS = 10_000L
private const val SUBSCRIPTION_TIMEOUT_MILLIS = 10_000L
private const val COMMAND_WRITE_TIMEOUT_MILLIS = 10_000L
private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@Suppress("DEPRECATION", "LargeClass", "LongMethod", "ReturnCount")
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
    private var readTimeout: Runnable? = null
    private var subscriptionTimeout: Runnable? = null
    private var commandWriteTimeout: Runnable? = null
    private val operationQueue = GattOperationQueue()
    private var nextGattOperationId = 1L
    private val queuedReads = mutableMapOf<Long, PendingCharacteristicRead>()
    private val queuedSubscriptions = mutableMapOf<Long, PendingSubscription>()
    private val queuedCommandWrites = mutableMapOf<Long, PendingCommandWrite>()
    private var activeRead: PendingCharacteristicRead? = null
    private var pendingSubscription: PendingSubscription? = null
    private var activeSubscription: BleCharacteristicSubscription? = null
    private var activeCommandWrite: PendingCommandWrite? = null

    @SuppressLint("MissingPermission")
    fun connect(
        device: DiscoveredBleDevice,
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onServicesChanged: (BleServiceDiscoveryStatus, List<BleGattService>, String) -> Unit,
        onCharacteristicReadChanged: (BleCharacteristicReadStatus, BleCharacteristicReadResult?, String) -> Unit,
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onCommandWriteChanged: (BleCommandWriteStatus, String, Int) -> Unit,
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
            onCharacteristicReadChanged,
            onSubscriptionChanged,
            onCommandWriteChanged,
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
    fun enqueueCharacteristicRead(
        request: BleCharacteristicReadRequest,
        onCharacteristicReadChanged: (BleCharacteristicReadStatus, BleCharacteristicReadResult?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ): BleCharacteristicReadStartResult {
        val missingPermissions = context.missingBleConnectPermissions()
        if (missingPermissions.isNotEmpty()) {
            return BleCharacteristicReadStartResult.PermissionMissing(missingPermissions)
        }

        val gatt = bluetoothGatt ?: return BleCharacteristicReadStartResult.NoActiveGatt
        if (status != BleConnectionStatus.Connected) {
            return BleCharacteristicReadStartResult.NotConnected(status)
        }

        val target = findGattTarget(gatt, request.serviceUuid, request.characteristicUuid)
            ?: return BleCharacteristicReadStartResult.CharacteristicUnavailable
        if (!target.characteristic.supportsRead()) {
            return BleCharacteristicReadStartResult.UnsupportedProperty
        }

        val operation = nextGattOperation(
            type = GattOperationType.Read,
            serviceUuid = request.serviceUuid,
            characteristicUuid = request.characteristicUuid,
            label = "read characteristic",
        )
        queuedReads[operation.id] = PendingCharacteristicRead(
            request = request,
            characteristic = target.characteristic,
        )
        val snapshot = operationQueue.enqueue(operation)
        logQueueState(
            callbackName = "GattOperationQueue.enqueue",
            gattStatus = "queued",
            gatt = gatt,
            operation = operation,
            snapshot = snapshot,
            message = "queued read queueDepth=${snapshot.totalDepth}",
            onLog = onLog,
        )
        onCharacteristicReadChanged(
            BleCharacteristicReadStatus.Queued,
            null,
            "Queued characteristic read. queueDepth=${snapshot.totalDepth}.",
        )
        startNextGattOperationIfIdle(
            gatt = gatt,
            onServicesChanged = null,
            onCharacteristicReadChanged = onCharacteristicReadChanged,
            onSubscriptionChanged = null,
            onCommandWriteChanged = null,
            onLog = onLog,
        )
        return BleCharacteristicReadStartResult.Enqueued
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

        val operation = nextGattOperation(
            type = GattOperationType.Subscribe,
            serviceUuid = subscription.serviceUuid,
            characteristicUuid = subscription.characteristicUuid,
            label = "subscribe ${subscription.mode.name.lowercase()}",
        )
        val pending = PendingSubscription(
            subscription = subscription,
            enable = true,
            characteristic = target.characteristic,
            descriptor = descriptor,
            operationId = operation.id,
        )
        queuedSubscriptions[operation.id] = pending
        val snapshot = operationQueue.enqueue(operation)
        onSubscriptionChanged(
            BleSubscriptionStatus.Queued,
            subscription,
            "Queued ${subscription.mode.name.lowercase()} subscription. queueDepth=${snapshot.totalDepth}.",
        )
        logQueueState(
            callbackName = "GattOperationQueue.enqueue",
            gattStatus = "queued",
            gatt = gatt,
            operation = operation,
            snapshot = snapshot,
            message = "queued subscribe mode=${subscription.mode.name} queueDepth=${snapshot.totalDepth}",
            onLog = onLog,
        )
        startNextGattOperationIfIdle(
            gatt = gatt,
            onServicesChanged = null,
            onCharacteristicReadChanged = null,
            onSubscriptionChanged = onSubscriptionChanged,
            onCommandWriteChanged = null,
            onLog = onLog,
        )
        return BleSubscriptionStartResult.Enqueued
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

        val subscription = activeSubscription ?: return BleSubscriptionStartResult.NoActiveSubscription
        val gatt = bluetoothGatt ?: return BleSubscriptionStartResult.NoActiveGatt
        if (status != BleConnectionStatus.Connected) {
            return BleSubscriptionStartResult.NotConnected(status)
        }

        val target = findGattTarget(gatt, subscription.serviceUuid, subscription.characteristicUuid)
            ?: return BleSubscriptionStartResult.CharacteristicUnavailable
        val descriptor = target.characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            ?: return BleSubscriptionStartResult.CccdUnavailable
        val operation = nextGattOperation(
            type = GattOperationType.Unsubscribe,
            serviceUuid = subscription.serviceUuid,
            characteristicUuid = subscription.characteristicUuid,
            label = "unsubscribe",
        )
        val pending = PendingSubscription(
            subscription = subscription,
            enable = false,
            characteristic = target.characteristic,
            descriptor = descriptor,
            operationId = operation.id,
        )
        queuedSubscriptions[operation.id] = pending
        val snapshot = operationQueue.enqueue(operation)
        onSubscriptionChanged(
            BleSubscriptionStatus.Queued,
            subscription,
            "Queued unsubscribe from ${subscription.characteristicUuid}. queueDepth=${snapshot.totalDepth}.",
        )
        logQueueState(
            callbackName = "GattOperationQueue.enqueue",
            gattStatus = "queued",
            gatt = gatt,
            operation = operation,
            snapshot = snapshot,
            message = "queued unsubscribe queueDepth=${snapshot.totalDepth}",
            onLog = onLog,
        )
        startNextGattOperationIfIdle(
            gatt = gatt,
            onServicesChanged = null,
            onCharacteristicReadChanged = null,
            onSubscriptionChanged = onSubscriptionChanged,
            onCommandWriteChanged = null,
            onLog = onLog,
        )
        return BleSubscriptionStartResult.Enqueued
    }

    @SuppressLint("MissingPermission")
    fun enqueueCommandWrite(
        request: BleCommandWriteRequest,
        onCommandWriteChanged: (BleCommandWriteStatus, String, Int) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ): BleCommandWriteStartResult {
        val missingPermissions = context.missingBleConnectPermissions()
        if (missingPermissions.isNotEmpty()) {
            return BleCommandWriteStartResult.PermissionMissing(missingPermissions)
        }

        val gatt = bluetoothGatt ?: return BleCommandWriteStartResult.NoActiveGatt
        if (status != BleConnectionStatus.Connected) {
            return BleCommandWriteStartResult.NotConnected(status)
        }

        val target = findGattTarget(gatt, request.serviceUuid, request.characteristicUuid)
            ?: return BleCommandWriteStartResult.CharacteristicUnavailable
        if (!target.characteristic.supports(request.writeType)) {
            return BleCommandWriteStartResult.UnsupportedProperty
        }

        val operation = nextGattOperation(
            type = GattOperationType.Write,
            serviceUuid = request.serviceUuid,
            characteristicUuid = request.characteristicUuid,
            label = request.commandName,
        )
        val pending = PendingCommandWrite(
            request = request,
            characteristic = target.characteristic,
            operationId = operation.id,
        )
        queuedCommandWrites[operation.id] = pending
        val snapshot = operationQueue.enqueue(operation)
        val queueDepth = gattQueueDepth()
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "GattOperationQueue.enqueue",
                gattStatus = "queued",
                connectionState = status.name,
                operationType = "write",
                targetDevice = activeDevice?.address ?: gatt.device.address,
                characteristicUuid = request.characteristicUuid,
                message = "queued command=${request.commandName} type=${request.writeType.name} " +
                    "bytes=${request.payload.size} value=${request.payload.toDisplayHex()} " +
                    "active=${snapshot.activeOperation?.type?.logName ?: "none"} " +
                    "queued=${snapshot.queuedCount} queueDepth=$queueDepth",
            ),
        )
        onCommandWriteChanged(
            BleCommandWriteStatus.Queued,
            "Queued ${request.commandName} write. queueDepth=$queueDepth.",
            queueDepth,
        )
        startNextGattOperationIfIdle(
            gatt = gatt,
            onServicesChanged = null,
            onCharacteristicReadChanged = null,
            onSubscriptionChanged = null,
            onCommandWriteChanged = onCommandWriteChanged,
            onLog = onLog,
        )
        return BleCommandWriteStartResult.Enqueued
    }

    @SuppressLint("MissingPermission")
    fun disconnect(
        onStateChanged: (BleConnectionStatus, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        if (hasActiveGattOperation() || hasQueuedGattOperations()) {
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BleConnectionManager.disconnect",
                    gattStatus = "operationActive",
                    connectionState = status.name,
                    operationType = "disconnect",
                    targetDevice = activeDevice?.address ?: "unknown",
                    message = "disconnect blocked because GATT queue is not empty queueDepth=${gattQueueDepth()}",
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
        onCharacteristicReadChanged: (BleCharacteristicReadStatus, BleCharacteristicReadResult?, String) -> Unit,
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onCommandWriteChanged: (BleCommandWriteStatus, String, Int) -> Unit,
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
                    enqueueServiceDiscovery(gatt, onServicesChanged, onLog)
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
                    clearReadState(
                        onCharacteristicReadChanged = onCharacteristicReadChanged,
                        message = "No pending characteristic reads.",
                    )
                    clearSubscriptionState(
                        onSubscriptionChanged = onSubscriptionChanged,
                        message = "No active notification subscription.",
                    )
                    clearCommandWriteState(
                        onCommandWriteChanged = onCommandWriteChanged,
                        message = "No pending command writes.",
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
                    clearReadState(
                        onCharacteristicReadChanged = onCharacteristicReadChanged,
                        message = "Characteristic reads cleared after GATT failure.",
                    )
                    clearSubscriptionState(
                        onSubscriptionChanged = onSubscriptionChanged,
                        message = "Notification subscription cleared after GATT failure.",
                    )
                    clearCommandWriteState(
                        onCommandWriteChanged = onCommandWriteChanged,
                        message = "Command writes cleared after GATT failure.",
                    )
                    updateStatus(
                        status = BleConnectionStatus.Failed,
                        message = "GATT callback failed with status $gattStatus.",
                        onStateChanged = onStateChanged,
                    )
                }
            }
        }

        // サービス探索
        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            gattStatus: Int,
        ) {
            serviceDiscoveryTimeout?.let(handler::removeCallbacks)
            serviceDiscoveryTimeout = null
            completeActiveGattOperation(
                gatt = gatt,
                expectedType = GattOperationType.ServiceDiscovery,
                gattStatus = gattStatus.toString(),
                callbackName = "BluetoothGattCallback.onServicesDiscovered",
                onLog = onLog,
            )

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
            startNextGattOperationIfIdle(
                gatt = gatt,
                onServicesChanged = onServicesChanged,
                onCharacteristicReadChanged = onCharacteristicReadChanged,
                onSubscriptionChanged = onSubscriptionChanged,
                onCommandWriteChanged = onCommandWriteChanged,
                onLog = onLog,
            )
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            gattStatus: Int,
        ) {
            onCharacteristicRead(
                gatt = gatt,
                characteristic = characteristic,
                value = characteristic.value ?: byteArrayOf(),
                gattStatus = gattStatus,
            )
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            gattStatus: Int,
        ) {
            val pending = activeRead
            if (pending == null || characteristic.uuid.toString() != pending.request.characteristicUuid) {
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BluetoothGattCallback.onCharacteristicRead",
                        gattStatus = gattStatus.toString(),
                        connectionState = status.name,
                        operationType = "read",
                        targetDevice = activeDevice?.address ?: gatt.device.address,
                        characteristicUuid = characteristic.uuid.toString(),
                        message = "ignored characteristic read callback",
                    ),
                )
                return
            }

            readTimeout?.let(handler::removeCallbacks)
            readTimeout = null
            activeRead = null
            completeActiveGattOperation(
                gatt = gatt,
                expectedType = GattOperationType.Read,
                gattStatus = gattStatus.toString(),
                callbackName = "BluetoothGattCallback.onCharacteristicRead",
                onLog = onLog,
            )

            val success = gattStatus == BluetoothGatt.GATT_SUCCESS
            val serviceUuid = characteristic.service?.uuid?.toString() ?: pending.request.serviceUuid
            val valueHex = value.toDisplayHex()
            val result = if (success) {
                BleCharacteristicReadResult(
                    timestampMillis = now(),
                    targetDevice = activeDevice?.address ?: gatt.device.address,
                    serviceUuid = serviceUuid,
                    characteristicUuid = pending.request.characteristicUuid,
                    valueHex = valueHex,
                    byteCount = value.size,
                )
            } else {
                null
            }
            val nextStatus = if (success) {
                BleCharacteristicReadStatus.Succeeded
            } else {
                BleCharacteristicReadStatus.Failed
            }
            val message = if (success) {
                "characteristic read succeeded bytes=${value.size} value=$valueHex queueDepth=${gattQueueDepth()}"
            } else {
                "characteristic read failed status=$gattStatus queueDepth=${gattQueueDepth()}"
            }
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BluetoothGattCallback.onCharacteristicRead",
                    gattStatus = gattStatus.toString(),
                    connectionState = status.name,
                    operationType = "read",
                    targetDevice = activeDevice?.address ?: gatt.device.address,
                    characteristicUuid = pending.request.characteristicUuid,
                    message = message,
                ),
            )
            onCharacteristicReadChanged(nextStatus, result, message)
            startNextGattOperationIfIdle(
                gatt = gatt,
                onServicesChanged = onServicesChanged,
                onCharacteristicReadChanged = onCharacteristicReadChanged,
                onSubscriptionChanged = onSubscriptionChanged,
                onCommandWriteChanged = onCommandWriteChanged,
                onLog = onLog,
            )
        }

        // CCCD書き込み結果を受け取る
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            gattStatus: Int,
        ) {
            // 待っていたCCCD書き込み結果かチェック
            val pending = pendingSubscription
            if (
                // subscribe/unsubscribe操作中でない
                pending == null ||
                // 書き込まれたUUIDがCCCDのものではない
                descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID ||
                // 書き込まれたCCCDがpendingのキャラクタリスティックのものではない
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

            // タイムアウトを解除
            subscriptionTimeout?.let(handler::removeCallbacks)
            subscriptionTimeout = null
            pendingSubscription = null
            completeActiveGattOperation(
                gatt = gatt,
                expectedType = if (pending.enable) GattOperationType.Subscribe else GattOperationType.Unsubscribe,
                gattStatus = gattStatus.toString(),
                callbackName = "BluetoothGattCallback.onDescriptorWrite",
                onLog = onLog,
            )

            // 書き込み結果が成功か確認
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
                // unsubscribe失敗時は、Peripheral側でCCCDが有効（Notify/Indicate有効）である可能性があるため、購読を継続
                else -> activeSubscription
            }

            if (success) {
                activeSubscription = nextSubscription
            } else if (pending.enable) {
                // subscribe失敗時、Central側のCCCD受け取り設定をOFF
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
            startNextGattOperationIfIdle(
                gatt = gatt,
                onServicesChanged = onServicesChanged,
                onCharacteristicReadChanged = onCharacteristicReadChanged,
                onSubscriptionChanged = onSubscriptionChanged,
                onCommandWriteChanged = onCommandWriteChanged,
                onLog = onLog,
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            gattStatus: Int,
        ) {
            val pending = activeCommandWrite
            if (pending == null || characteristic.uuid.toString() != pending.request.characteristicUuid) {
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BluetoothGattCallback.onCharacteristicWrite",
                        gattStatus = gattStatus.toString(),
                        connectionState = status.name,
                        operationType = "write",
                        targetDevice = activeDevice?.address ?: gatt.device.address,
                        characteristicUuid = characteristic.uuid.toString(),
                        message = "ignored characteristic write callback",
                    ),
                )
                return
            }

            commandWriteTimeout?.let(handler::removeCallbacks)
            commandWriteTimeout = null
            activeCommandWrite = null
            completeActiveGattOperation(
                gatt = gatt,
                expectedType = GattOperationType.Write,
                gattStatus = gattStatus.toString(),
                callbackName = "BluetoothGattCallback.onCharacteristicWrite",
                onLog = onLog,
            )

            val success = gattStatus == BluetoothGatt.GATT_SUCCESS
            val nextStatus = if (success) {
                BleCommandWriteStatus.Succeeded
            } else {
                BleCommandWriteStatus.Failed
            }
            val message = commandWriteCallbackMessage(
                pending = pending,
                gattStatus = gattStatus,
                queueDepth = gattQueueDepth(),
            )
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = "BluetoothGattCallback.onCharacteristicWrite",
                    gattStatus = gattStatus.toString(),
                    connectionState = status.name,
                    operationType = "write",
                    targetDevice = activeDevice?.address ?: gatt.device.address,
                    characteristicUuid = pending.request.characteristicUuid,
                    message = message,
                ),
            )
            onCommandWriteChanged(nextStatus, message, gattQueueDepth())
            startNextGattOperationIfIdle(
                gatt = gatt,
                onServicesChanged = onServicesChanged,
                onCharacteristicReadChanged = onCharacteristicReadChanged,
                onSubscriptionChanged = onSubscriptionChanged,
                onCommandWriteChanged = onCommandWriteChanged,
                onLog = onLog,
            )
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
    private fun enqueueServiceDiscovery(
        gatt: BluetoothGatt,
        onServicesChanged: (BleServiceDiscoveryStatus, List<BleGattService>, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        val operation = nextGattOperation(
            type = GattOperationType.ServiceDiscovery,
            label = "service discovery",
        )
        val snapshot = operationQueue.enqueue(operation)
        logQueueState(
            callbackName = "GattOperationQueue.enqueue",
            gattStatus = "queued",
            gatt = gatt,
            operation = operation,
            snapshot = snapshot,
            message = "queued service discovery queueDepth=${snapshot.totalDepth}",
            onLog = onLog,
        )
        startNextGattOperationIfIdle(
            gatt = gatt,
            onServicesChanged = onServicesChanged,
            onCharacteristicReadChanged = null,
            onSubscriptionChanged = null,
            onCommandWriteChanged = null,
            onLog = onLog,
        )
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
            completeActiveGattOperation(
                gatt = gatt,
                expectedType = GattOperationType.ServiceDiscovery,
                gattStatus = "startFailed",
                callbackName = "BluetoothGatt.discoverServices",
                onLog = onLog,
            )
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
                completeActiveGattOperation(
                    gatt = gatt,
                    expectedType = GattOperationType.ServiceDiscovery,
                    gattStatus = "timeout",
                    callbackName = "BleConnectionManager.serviceDiscoveryTimeout",
                    onLog = onLog,
                )
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
                completeActiveGattOperation(
                    gatt = gatt,
                    expectedType = if (pending.enable) GattOperationType.Subscribe else GattOperationType.Unsubscribe,
                    gattStatus = "startFailed",
                    callbackName = "BluetoothGatt.writeDescriptor",
                    onLog = onLog,
                )
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
                completeActiveGattOperation(
                    gatt = gatt,
                    expectedType = if (pending.enable) GattOperationType.Subscribe else GattOperationType.Unsubscribe,
                    gattStatus = "timeout",
                    callbackName = "BleConnectionManager.subscriptionTimeout",
                    onLog = onLog,
                )
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

    @SuppressLint("MissingPermission")
    private fun startNextGattOperationIfIdle(
        gatt: BluetoothGatt,
        onServicesChanged: ((BleServiceDiscoveryStatus, List<BleGattService>, String) -> Unit)?,
        onCharacteristicReadChanged: ((BleCharacteristicReadStatus, BleCharacteristicReadResult?, String) -> Unit)?,
        onSubscriptionChanged: ((BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit)?,
        onCommandWriteChanged: ((BleCommandWriteStatus, String, Int) -> Unit)?,
        onLog: (BleLogEntry) -> Unit,
    ) {
        val operation = operationQueue.startNextIfIdle() ?: return
        logQueueState(
            callbackName = "GattOperationQueue.start",
            gattStatus = "started",
            gatt = gatt,
            operation = operation,
            snapshot = operationQueue.snapshot(),
            message = "started ${operation.type.logName} queueDepth=${gattQueueDepth()}",
            onLog = onLog,
        )
        when (operation.type) {
            GattOperationType.ServiceDiscovery -> {
                if (onServicesChanged == null) {
                    failOperationWithoutCallback(gatt, operation, onLog)
                } else {
                    startServiceDiscovery(gatt, onServicesChanged, onLog)
                }
            }

            GattOperationType.Read -> {
                val pending = queuedReads.remove(operation.id)
                if (pending == null || onCharacteristicReadChanged == null) {
                    failOperationWithoutCallback(gatt, operation, onLog)
                } else {
                    startQueuedCharacteristicRead(gatt, pending, onCharacteristicReadChanged, onLog)
                }
            }

            GattOperationType.Subscribe,
            GattOperationType.Unsubscribe,
            -> {
                val pending = queuedSubscriptions.remove(operation.id)
                if (pending == null || onSubscriptionChanged == null) {
                    failOperationWithoutCallback(gatt, operation, onLog)
                } else {
                    startQueuedSubscriptionWrite(gatt, pending, onSubscriptionChanged, onLog)
                }
            }

            GattOperationType.Write -> {
                val pending = queuedCommandWrites.remove(operation.id)
                if (pending == null || onCommandWriteChanged == null) {
                    failOperationWithoutCallback(gatt, operation, onLog)
                } else {
                    startQueuedCommandWrite(gatt, pending, onCommandWriteChanged, onLog)
                }
            }
        }
    }

    private fun startQueuedSubscriptionWrite(
        gatt: BluetoothGatt,
        pending: PendingSubscription,
        onSubscriptionChanged: (BleSubscriptionStatus, BleCharacteristicSubscription?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        pendingSubscription = pending
        val nextStatus = if (pending.enable) {
            BleSubscriptionStatus.Subscribing
        } else {
            BleSubscriptionStatus.Unsubscribing
        }
        onSubscriptionChanged(
            nextStatus,
            pending.subscription,
            "${if (pending.enable) "Subscribe" else "Unsubscribe"} started for " +
                "${pending.subscription.characteristicUuid}.",
        )
        tryStartSubscriptionWrite(
            gatt = gatt,
            pending = pending,
            descriptorValue = if (pending.enable) {
                pending.subscription.mode.enableDescriptorValue()
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            },
            startFailureSubscription = if (pending.enable) null else activeSubscription,
            onSubscriptionChanged = onSubscriptionChanged,
            onLog = onLog,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startQueuedCharacteristicRead(
        gatt: BluetoothGatt,
        pending: PendingCharacteristicRead,
        onCharacteristicReadChanged: (BleCharacteristicReadStatus, BleCharacteristicReadResult?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        activeRead = pending
        val request = pending.request
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothGatt.readCharacteristic",
                connectionState = status.name,
                operationType = "read",
                targetDevice = activeDevice?.address ?: gatt.device.address,
                characteristicUuid = request.characteristicUuid,
                message = "read requested queueDepth=${gattQueueDepth()}",
            ),
        )

        try {
            if (gatt.readCharacteristic(pending.characteristic)) {
                onCharacteristicReadChanged(
                    BleCharacteristicReadStatus.Reading,
                    null,
                    "Reading ${request.characteristicUuid}. queueDepth=${gattQueueDepth()}.",
                )
                scheduleReadTimeout(gatt, pending, onCharacteristicReadChanged, onLog)
            } else {
                failStartedCharacteristicRead(
                    gatt = gatt,
                    pending = pending,
                    gattStatus = "readNotStarted",
                    message = "readCharacteristic could not be started.",
                    onCharacteristicReadChanged = onCharacteristicReadChanged,
                    onLog = onLog,
                )
            }
        } catch (exception: SecurityException) {
            failStartedCharacteristicRead(
                gatt = gatt,
                pending = pending,
                gattStatus = "permissionDenied",
                message = exception.message ?: "Missing permission while reading characteristic",
                onCharacteristicReadChanged = onCharacteristicReadChanged,
                onLog = onLog,
            )
        } catch (exception: RuntimeException) {
            failStartedCharacteristicRead(
                gatt = gatt,
                pending = pending,
                gattStatus = "readError",
                message = exception.message ?: "Unable to read characteristic",
                onCharacteristicReadChanged = onCharacteristicReadChanged,
                onLog = onLog,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startQueuedCommandWrite(
        gatt: BluetoothGatt,
        pending: PendingCommandWrite,
        onCommandWriteChanged: (BleCommandWriteStatus, String, Int) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        activeCommandWrite = pending
        val request = pending.request
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothGatt.writeCharacteristic",
                connectionState = status.name,
                operationType = "write",
                targetDevice = activeDevice?.address ?: gatt.device.address,
                characteristicUuid = request.characteristicUuid,
                message = "write requested command=${request.commandName} type=${request.writeType.name} " +
                    "bytes=${request.payload.size} value=${request.payload.toDisplayHex()} " +
                    "queueDepth=${gattQueueDepth()}",
            ),
        )

        try {
            val started = writeCharacteristic(
                gatt = gatt,
                characteristic = pending.characteristic,
                value = request.payload,
                writeType = request.writeType,
            )
            if (started) {
                onCommandWriteChanged(
                    BleCommandWriteStatus.Writing,
                    "Writing ${request.commandName}. queueDepth=${gattQueueDepth()}.",
                    gattQueueDepth(),
                )
                scheduleCommandWriteTimeout(gatt, pending, onCommandWriteChanged, onLog)
            } else {
                failStartedCommandWrite(
                    gatt = gatt,
                    pending = pending,
                    gattStatus = "writeNotStarted",
                    message = "writeCharacteristic could not be started.",
                    onCommandWriteChanged = onCommandWriteChanged,
                    onLog = onLog,
                )
            }
        } catch (exception: SecurityException) {
            failStartedCommandWrite(
                gatt = gatt,
                pending = pending,
                gattStatus = "permissionDenied",
                message = exception.message ?: "Missing permission while writing command",
                onCommandWriteChanged = onCommandWriteChanged,
                onLog = onLog,
            )
        } catch (exception: RuntimeException) {
            failStartedCommandWrite(
                gatt = gatt,
                pending = pending,
                gattStatus = "writeError",
                message = exception.message ?: "Unable to write command",
                onCommandWriteChanged = onCommandWriteChanged,
                onLog = onLog,
            )
        }
    }

    private fun scheduleReadTimeout(
        gatt: BluetoothGatt,
        pending: PendingCharacteristicRead,
        onCharacteristicReadChanged: (BleCharacteristicReadStatus, BleCharacteristicReadResult?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        readTimeout?.let(handler::removeCallbacks)
        readTimeout = null
        val timeout = Runnable {
            if (activeRead == pending) {
                activeRead = null
                completeActiveGattOperation(
                    gatt = gatt,
                    expectedType = GattOperationType.Read,
                    gattStatus = "timeout",
                    callbackName = "BleConnectionManager.readTimeout",
                    onLog = onLog,
                )
                val request = pending.request
                val message = "characteristic read timeout after ${READ_TIMEOUT_MILLIS}ms"
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BleConnectionManager.readTimeout",
                        gattStatus = "timeout",
                        connectionState = status.name,
                        operationType = "read",
                        targetDevice = activeDevice?.address ?: gatt.device.address,
                        characteristicUuid = request.characteristicUuid,
                        message = message,
                    ),
                )
                onCharacteristicReadChanged(BleCharacteristicReadStatus.Failed, null, message)
            }
        }
        readTimeout = timeout
        handler.postDelayed(timeout, READ_TIMEOUT_MILLIS)
    }

    private fun failStartedCharacteristicRead(
        gatt: BluetoothGatt,
        pending: PendingCharacteristicRead,
        gattStatus: String,
        message: String,
        onCharacteristicReadChanged: (BleCharacteristicReadStatus, BleCharacteristicReadResult?, String) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        activeRead = null
        completeActiveGattOperation(
            gatt = gatt,
            expectedType = GattOperationType.Read,
            gattStatus = gattStatus,
            callbackName = "BluetoothGatt.readCharacteristic",
            onLog = onLog,
        )
        val request = pending.request
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothGatt.readCharacteristic",
                gattStatus = gattStatus,
                connectionState = status.name,
                operationType = "read",
                targetDevice = activeDevice?.address ?: gatt.device.address,
                characteristicUuid = request.characteristicUuid,
                message = message,
            ),
        )
        onCharacteristicReadChanged(BleCharacteristicReadStatus.Failed, null, message)
    }

    private fun scheduleCommandWriteTimeout(
        gatt: BluetoothGatt,
        pending: PendingCommandWrite,
        onCommandWriteChanged: (BleCommandWriteStatus, String, Int) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        commandWriteTimeout?.let(handler::removeCallbacks)
        commandWriteTimeout = null
        val timeout = Runnable {
            if (activeCommandWrite == pending) {
                activeCommandWrite = null
                completeActiveGattOperation(
                    gatt = gatt,
                    expectedType = GattOperationType.Write,
                    gattStatus = "timeout",
                    callbackName = "BleConnectionManager.commandWriteTimeout",
                    onLog = onLog,
                )
                val request = pending.request
                val message = "command write timeout after ${COMMAND_WRITE_TIMEOUT_MILLIS}ms " +
                    "command=${request.commandName} value=${request.payload.toDisplayHex()}"
                onLog(
                    connectionLog(
                        timestampMillis = now(),
                        callbackName = "BleConnectionManager.commandWriteTimeout",
                        gattStatus = "timeout",
                        connectionState = status.name,
                        operationType = "write",
                        targetDevice = activeDevice?.address ?: gatt.device.address,
                        characteristicUuid = request.characteristicUuid,
                        message = message,
                    ),
                )
                onCommandWriteChanged(BleCommandWriteStatus.Failed, message, gattQueueDepth())
                startNextGattOperationIfIdle(
                    gatt = gatt,
                    onServicesChanged = null,
                    onCharacteristicReadChanged = null,
                    onSubscriptionChanged = null,
                    onCommandWriteChanged = onCommandWriteChanged,
                    onLog = onLog,
                )
            }
        }
        commandWriteTimeout = timeout
        handler.postDelayed(timeout, COMMAND_WRITE_TIMEOUT_MILLIS)
    }

    private fun failStartedCommandWrite(
        gatt: BluetoothGatt,
        pending: PendingCommandWrite,
        gattStatus: String,
        message: String,
        onCommandWriteChanged: (BleCommandWriteStatus, String, Int) -> Unit,
        onLog: (BleLogEntry) -> Unit,
    ) {
        activeCommandWrite = null
        completeActiveGattOperation(
            gatt = gatt,
            expectedType = GattOperationType.Write,
            gattStatus = gattStatus,
            callbackName = "BluetoothGatt.writeCharacteristic",
            onLog = onLog,
        )
        val request = pending.request
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = "BluetoothGatt.writeCharacteristic",
                gattStatus = gattStatus,
                connectionState = status.name,
                operationType = "write",
                targetDevice = activeDevice?.address ?: gatt.device.address,
                characteristicUuid = request.characteristicUuid,
                message = "$message command=${request.commandName} value=${request.payload.toDisplayHex()}",
            ),
        )
        onCommandWriteChanged(BleCommandWriteStatus.Failed, message, gattQueueDepth())
        startNextGattOperationIfIdle(
            gatt = gatt,
            onServicesChanged = null,
            onCharacteristicReadChanged = null,
            onSubscriptionChanged = null,
            onCommandWriteChanged = onCommandWriteChanged,
            onLog = onLog,
        )
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
        readTimeout?.let(handler::removeCallbacks)
        readTimeout = null
        subscriptionTimeout?.let(handler::removeCallbacks)
        subscriptionTimeout = null
        commandWriteTimeout?.let(handler::removeCallbacks)
        commandWriteTimeout = null
        operationQueue.clear()
        queuedReads.clear()
        queuedSubscriptions.clear()
        queuedCommandWrites.clear()
        activeRead = null
        pendingSubscription = null
        activeCommandWrite = null
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
            activeRead = null
            activeCommandWrite = null
            queuedReads.clear()
            queuedSubscriptions.clear()
            queuedCommandWrites.clear()
            operationQueue.clear()
        }
    }

    private fun clearReadState(
        onCharacteristicReadChanged: (BleCharacteristicReadStatus, BleCharacteristicReadResult?, String) -> Unit,
        message: String,
    ) {
        readTimeout?.let(handler::removeCallbacks)
        readTimeout = null
        activeRead = null
        queuedReads.clear()
        onCharacteristicReadChanged(BleCharacteristicReadStatus.Idle, null, message)
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

    private fun clearCommandWriteState(
        onCommandWriteChanged: (BleCommandWriteStatus, String, Int) -> Unit,
        message: String,
    ) {
        commandWriteTimeout?.let(handler::removeCallbacks)
        commandWriteTimeout = null
        activeCommandWrite = null
        queuedCommandWrites.clear()
        onCommandWriteChanged(BleCommandWriteStatus.Idle, message, gattQueueDepth())
    }

    private fun gattQueueDepth(): Int = operationQueue.snapshot().totalDepth

    private fun hasActiveGattOperation(): Boolean = operationQueue.snapshot().activeOperation != null

    private fun hasQueuedGattOperations(): Boolean = operationQueue.snapshot().queuedOperations.isNotEmpty()

    private fun nextGattOperation(
        type: GattOperationType,
        serviceUuid: String? = null,
        characteristicUuid: String? = null,
        label: String = type.logName,
    ): GattOperation {
        return GattOperation(
            id = nextGattOperationId++,
            type = type,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            label = label,
        )
    }

    private fun completeActiveGattOperation(
        gatt: BluetoothGatt,
        expectedType: GattOperationType,
        gattStatus: String,
        callbackName: String,
        onLog: (BleLogEntry) -> Unit,
    ) {
        val active = operationQueue.snapshot().activeOperation
        if (active == null || active.type != expectedType) {
            onLog(
                connectionLog(
                    timestampMillis = now(),
                    callbackName = callbackName,
                    gattStatus = "unexpectedOperation",
                    connectionState = status.name,
                    operationType = expectedType.logName,
                    targetDevice = activeDevice?.address ?: gatt.device.address,
                    characteristicUuid = active?.characteristicUuidForLog() ?: "N/A",
                    message = "callback did not match active operation expected=${expectedType.logName} " +
                        "active=${active?.type?.logName ?: "none"}",
                ),
            )
            return
        }

        operationQueue.completeActive(active.id)
        logQueueState(
            callbackName = callbackName,
            gattStatus = gattStatus,
            gatt = gatt,
            operation = active,
            snapshot = operationQueue.snapshot(),
            message = "completed ${active.type.logName} queueDepth=${gattQueueDepth()}",
            onLog = onLog,
        )
    }

    private fun failOperationWithoutCallback(
        gatt: BluetoothGatt,
        operation: GattOperation,
        onLog: (BleLogEntry) -> Unit,
    ) {
        operationQueue.completeActive(operation.id)
        logQueueState(
            callbackName = "GattOperationQueue.start",
            gattStatus = "missingCallback",
            gatt = gatt,
            operation = operation,
            snapshot = operationQueue.snapshot(),
            message = "operation could not start because required callback was unavailable",
            onLog = onLog,
        )
    }

    private fun logQueueState(
        callbackName: String,
        gattStatus: String,
        gatt: BluetoothGatt,
        operation: GattOperation,
        snapshot: GattOperationQueueSnapshot,
        message: String,
        onLog: (BleLogEntry) -> Unit,
    ) {
        onLog(
            connectionLog(
                timestampMillis = now(),
                callbackName = callbackName,
                gattStatus = gattStatus,
                connectionState = status.name,
                operationType = operation.type.logName,
                targetDevice = activeDevice?.address ?: gatt.device.address,
                characteristicUuid = operation.characteristicUuidForLog(),
                message = "$message active=${snapshot.activeOperation?.type?.logName ?: "none"} " +
                    "queued=${snapshot.queuedCount}",
            ),
        )
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
        completeActiveGattOperation(
            gatt = gatt,
            expectedType = if (pending.enable) GattOperationType.Subscribe else GattOperationType.Unsubscribe,
            gattStatus = gattStatus,
            callbackName = "BluetoothGatt.writeDescriptor",
            onLog = onLog,
        )
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

enum class BleCharacteristicReadStatus {
    Idle,
    Queued,
    Reading,
    Succeeded,
    Failed,
}

enum class BleSubscriptionStatus {
    Idle,
    Queued,
    Subscribing,
    Subscribed,
    Unsubscribing,
    Failed,
}

enum class BleCommandWriteStatus {
    Idle,
    Queued,
    Writing,
    Succeeded,
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
    data object Enqueued : BleSubscriptionStartResult

    // 購読に成功
    data object Started : BleSubscriptionStartResult

    // 接続済みのBluetoothGattがない
    data object NoActiveGatt : BleSubscriptionStartResult

    // 解除対象の購読がない
    data object NoActiveSubscription : BleSubscriptionStartResult

    // GATTはあるが、接続状態がConnectedではない
    data class NotConnected(val status: BleConnectionStatus) : BleSubscriptionStartResult

    // すでにsubscribe/unsubscribe操作中
    data object OperationActive : BleSubscriptionStartResult

    // 指定されたCharacteristicが見つからない
    data object CharacteristicUnavailable : BleSubscriptionStartResult

    // そのCharacteristicは指定modeをサポートしていない
    data object UnsupportedProperty : BleSubscriptionStartResult

    // CharacteristicにCCCDがないのでsubscribeできない
    data object CccdUnavailable : BleSubscriptionStartResult

    // setCharacteristicNotification(characteristic, true) が false を返した
    data object LocalNotificationFailed : BleSubscriptionStartResult

    // Descriptorの書き込みが開始されなかった
    data object DescriptorWriteNotStarted : BleSubscriptionStartResult

    // BLUETOOTH_CONNECT権限がないのでsubscribeできない
    data class PermissionMissing(val missingPermissions: List<String>) : BleSubscriptionStartResult

    // descriptor write開始時に例外やエラーが起きた
    data class Error(val message: String) : BleSubscriptionStartResult
}

sealed interface BleCharacteristicReadStartResult {
    data object Enqueued : BleCharacteristicReadStartResult
    data object NoActiveGatt : BleCharacteristicReadStartResult
    data class NotConnected(val status: BleConnectionStatus) : BleCharacteristicReadStartResult
    data object CharacteristicUnavailable : BleCharacteristicReadStartResult
    data object UnsupportedProperty : BleCharacteristicReadStartResult
    data class PermissionMissing(val missingPermissions: List<String>) : BleCharacteristicReadStartResult
}

sealed interface BleCommandWriteStartResult {
    data object Enqueued : BleCommandWriteStartResult
    data object NoActiveGatt : BleCommandWriteStartResult
    data class NotConnected(val status: BleConnectionStatus) : BleCommandWriteStartResult
    data object CharacteristicUnavailable : BleCommandWriteStartResult
    data object UnsupportedProperty : BleCommandWriteStartResult
    data object OperationActive : BleCommandWriteStartResult
    data class PermissionMissing(val missingPermissions: List<String>) : BleCommandWriteStartResult
}

private data class PendingSubscription(
    val subscription: BleCharacteristicSubscription,
    val enable: Boolean,
    val characteristic: BluetoothGattCharacteristic,
    val descriptor: BluetoothGattDescriptor,
    val operationId: Long,
)

private data class PendingCharacteristicRead(
    val request: BleCharacteristicReadRequest,
    val characteristic: BluetoothGattCharacteristic,
)

private data class PendingCommandWrite(
    val request: BleCommandWriteRequest,
    val characteristic: BluetoothGattCharacteristic,
    val operationId: Long,
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
        BleSubscriptionMode.Notification -> properties hasProperty BluetoothGattCharacteristic.PROPERTY_NOTIFY
        BleSubscriptionMode.Indication -> properties hasProperty BluetoothGattCharacteristic.PROPERTY_INDICATE
    }
}

private fun BluetoothGattCharacteristic.supportsRead(): Boolean =
    properties hasProperty BluetoothGattCharacteristic.PROPERTY_READ

private fun BluetoothGattCharacteristic.supports(writeType: BleCharacteristicWriteType): Boolean {
    return when (writeType) {
        BleCharacteristicWriteType.Request ->
            properties hasProperty BluetoothGattCharacteristic.PROPERTY_WRITE

        BleCharacteristicWriteType.Command ->
            properties hasProperty BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    }
}

@Suppress("DEPRECATION")
private fun BleSubscriptionMode.enableDescriptorValue(): ByteArray {
    return when (this) {
        BleSubscriptionMode.Notification -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        BleSubscriptionMode.Indication -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
    }
}

private fun BleCharacteristicWriteType.androidWriteType(): Int {
    return when (this) {
        BleCharacteristicWriteType.Request -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        BleCharacteristicWriteType.Command -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    }
}

@SuppressLint("MissingPermission")
private fun setCharacteristicNotification(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    enable: Boolean,
    onLog: (BleLogEntry) -> Unit,
): Boolean {
    // Central側のCCCD受け取り設定 ON/OFF
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
private fun writeCharacteristic(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: BleCharacteristicWriteType,
): Boolean {
    val androidWriteType = writeType.androidWriteType()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, value, androidWriteType) == BluetoothStatusCodes.SUCCESS
    } else {
        characteristic.writeType = androidWriteType
        characteristic.value = value
        gatt.writeCharacteristic(characteristic)
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun writeDescriptor(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    value: ByteArray,
): Boolean {
    // CCCD書き込み要求開始
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        // 32以下では、CCCDへの書き込み内容をプロパティ指定しないといけない
        descriptor.value = value
        // 開始結果も成功（true）または失敗（false）のみ
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

private fun commandWriteCallbackMessage(
    pending: PendingCommandWrite,
    gattStatus: Int,
    queueDepth: Int,
): String {
    val request = pending.request
    return if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
        "command write succeeded command=${request.commandName} type=${request.writeType.name} " +
            "bytes=${request.payload.size} value=${request.payload.toDisplayHex()} " +
            "queueDepth=$queueDepth"
    } else {
        "command write failed command=${request.commandName} type=${request.writeType.name} " +
            "status=$gattStatus bytes=${request.payload.size} value=${request.payload.toDisplayHex()} " +
            "queueDepth=$queueDepth"
    }
}
