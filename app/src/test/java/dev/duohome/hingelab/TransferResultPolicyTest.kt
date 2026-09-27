package dev.duohome.hingelab
import org.junit.Assert.*
import org.junit.Test

class TransferResultPolicyTest {
    @Test fun preMoveRejectionAllowsNextGestureButNotSameGesture() {
        val gate=TransferCycleGate(); assertTrue(gate.begin())
        val line="TRANSFER NOT_MOVED requires foreground standard app on display 0"
        assertTrue(TransferResultPolicy.safeCompletion(line))
        if(TransferResultPolicy.safeCompletion(line)) gate.restored()
        assertFalse(gate.begin()); gate.boundary(); assertTrue(gate.begin())
    }
    @Test fun lateRejectionAfterEndpointRearms() {
        val gate=TransferCycleGate(); gate.begin(); gate.boundary()
        assertFalse(gate.begin())
        if(TransferResultPolicy.safeCompletion("TRANSFER NOT_MOVED no focused task")) gate.restored()
        assertTrue(gate.begin())
    }
    @Test fun uncertainMoveDoesNotCountAsSafeReturn() {
        for(line in listOf("TRANSFER REJECTED error during move", "TRANSFER MOVED task=1", "TRANSFER ERROR IOException", "TRANSFER RESTORE_FAILED unknown"))
            assertFalse(TransferResultPolicy.safeCompletion(line))
        assertTrue(TransferResultPolicy.failedCompletion("TRANSFER RESTORE_FAILED missing_terminal_result"))
        assertTrue(TransferResultPolicy.safeCompletion("TRANSFER RESTORED task=1 focused=1"))
    }
}
