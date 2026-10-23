package com.mino.app

import org.junit.Assert.*
import org.junit.Test

class RecoveryOperationsTest {
    @Test fun newerCompletionCannotAbandonAnOlderUnwrittenOperation() {
        val operations = RecoveryOperations()
        operations.begin("ui-A")
        operations.begin("receiver-B")
        assertFalse(operations.finish("receiver-B"))
        assertFalse(operations.isIdle())
        assertTrue(operations.finish("ui-A"))
        assertTrue(operations.isIdle())
    }
    @Test fun repairCannotRetireProtectionBeforeAMutationHasStarted() {
        val operations = RecoveryOperations()
        operations.begin("pending-save")
        assertFalse(operations.isIdle())
        assertFalse(operations.finish("unrelated-old-token"))
        assertFalse(operations.isIdle())
    }
    @Test fun aNewProcessHasNoUnfinishedInMemoryMutationToWaitFor() {
        assertTrue(RecoveryOperations().isIdle())
    }
    @Test fun delayedSuccessCannotCancelAnotherOperationsFailedWriteRepair() {
        val operations = RecoveryOperations()
        operations.begin("A")
        operations.begin("B")
        operations.abandon("A")
        assertFalse(operations.finish("B"))
        val stamp = operations.repairStamp()!!
        assertTrue(operations.repaired(stamp))
        operations.begin("C")
        assertTrue(operations.finish("C"))
    }
    @Test fun newWriteInvalidatesInFlightRepairAcknowledgement() {
        val operations = RecoveryOperations()
        val stamp = operations.repairStamp()!!
        operations.begin("new-save")
        operations.abandon("new-save")
        assertFalse(operations.repaired(stamp))
    }
}
