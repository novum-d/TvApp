package io.novumd.tvapp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GattOperationQueueTest {
    @Test
    fun startNextIfIdle_keepsOneActiveOperationAndLeavesLaterOperationsQueued() {
        val queue = GattOperationQueue()
        val read = operation(id = 1L, type = GattOperationType.Read)
        val write = operation(id = 2L, type = GattOperationType.Write)

        queue.enqueue(read)
        queue.enqueue(write)

        val started = queue.startNextIfIdle()
        val snapshot = queue.snapshot()

        assertEquals(read, started)
        assertEquals(read, snapshot.activeOperation)
        assertEquals(listOf(write), snapshot.queuedOperations)
        assertEquals(2, snapshot.totalDepth)
    }

    @Test
    fun startNextIfIdle_returnsNullWhileOperationIsActive() {
        val queue = GattOperationQueue()
        val read = operation(id = 1L, type = GattOperationType.Read)
        val write = operation(id = 2L, type = GattOperationType.Write)
        queue.enqueue(read)
        queue.enqueue(write)
        queue.startNextIfIdle()

        val started = queue.startNextIfIdle()

        assertNull(started)
        assertEquals(read, queue.activeOperation)
        assertEquals(listOf(write), queue.snapshot().queuedOperations)
    }

    @Test
    fun completeActive_allowsNextQueuedOperationToStart() {
        val queue = GattOperationQueue()
        val read = operation(id = 1L, type = GattOperationType.Read)
        val write = operation(id = 2L, type = GattOperationType.Write)
        queue.enqueue(read)
        queue.enqueue(write)
        queue.startNextIfIdle()

        val completed = queue.completeActive(read.id)
        val started = queue.startNextIfIdle()

        assertEquals(read, completed)
        assertEquals(write, started)
        assertEquals(write, queue.activeOperation)
        assertEquals(emptyList<GattOperation>(), queue.snapshot().queuedOperations)
    }

    @Test
    fun completeActive_ignoresMismatchedOperationId() {
        val queue = GattOperationQueue()
        val read = operation(id = 1L, type = GattOperationType.Read)
        queue.enqueue(read)
        queue.startNextIfIdle()

        val completed = queue.completeActive(operationId = 99L)

        assertNull(completed)
        assertEquals(read, queue.activeOperation)
    }

    private fun operation(
        id: Long,
        type: GattOperationType,
    ): GattOperation {
        return GattOperation(
            id = id,
            type = type,
            characteristicUuid = "00002a19-0000-1000-8000-00805f9b34fb",
        )
    }
}
