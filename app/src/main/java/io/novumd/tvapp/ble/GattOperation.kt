package io.novumd.tvapp.ble

private const val NO_CHARACTERISTIC_UUID = "N/A"

data class GattOperation(
    val id: Long,
    val type: GattOperationType,
    val serviceUuid: String? = null,
    val characteristicUuid: String? = null,
    val label: String = type.logName,
)

enum class GattOperationType(
    val logName: String,
) {
    ServiceDiscovery("serviceDiscovery"),
    Read("read"),
    Write("write"),
    Subscribe("subscribe"),
    Unsubscribe("unsubscribe"),
}

data class GattOperationQueueSnapshot(
    val activeOperation: GattOperation?,
    val queuedOperations: List<GattOperation>,
) {
    val activeCount: Int = if (activeOperation == null) 0 else 1
    val queuedCount: Int = queuedOperations.size
    val totalDepth: Int = activeCount + queuedCount
}

fun GattOperation.characteristicUuidForLog(): String = characteristicUuid ?: NO_CHARACTERISTIC_UUID
