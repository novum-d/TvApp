package io.novumd.tvapp.ble

class GattOperationQueue {
    private val pendingOperations = ArrayDeque<GattOperation>()

    var activeOperation: GattOperation? = null
        private set

    fun enqueue(operation: GattOperation): GattOperationQueueSnapshot {
        pendingOperations.addLast(operation)
        return snapshot()
    }

    fun startNextIfIdle(): GattOperation? {
        if (activeOperation != null || pendingOperations.isEmpty()) {
            return null
        }

        return pendingOperations.removeFirst().also { operation ->
            activeOperation = operation
        }
    }

    fun completeActive(operationId: Long): GattOperation? {
        val active = activeOperation ?: return null
        if (active.id != operationId) {
            return null
        }

        activeOperation = null
        return active
    }

    fun clear(): GattOperationQueueSnapshot {
        activeOperation = null
        pendingOperations.clear()
        return snapshot()
    }

    fun snapshot(): GattOperationQueueSnapshot {
        return GattOperationQueueSnapshot(
            activeOperation = activeOperation,
            queuedOperations = pendingOperations.toList(),
        )
    }
}
