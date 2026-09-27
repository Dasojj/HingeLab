package dev.duohome.hingelab
import org.junit.Assert.*
import org.junit.Test
class TransferCycleGateTest {
    @Test fun cannotRepeatDuringSameFold() {
        val g=TransferCycleGate(); assertTrue(g.begin()); assertFalse(g.begin())
        g.restored(); assertFalse(g.begin()); g.boundary(); assertTrue(g.begin())
    }
    @Test fun endpointBeforeRestorationCannotOverlapMoves() {
        val g=TransferCycleGate(); g.begin(); g.boundary(); assertFalse(g.begin())
        g.restored(); assertTrue(g.begin())
    }
    @Test fun unconfirmedRestorationNeverRearms() {
        val g=TransferCycleGate(); g.begin(); repeat(5) { g.boundary(); assertFalse(g.begin()) }
    }
    @Test fun eachCycleNeedsNewEndpoint() {
        val g=TransferCycleGate(); g.boundary(); assertTrue(g.begin()); g.restored(); assertFalse(g.begin())
        g.boundary(); assertTrue(g.begin()); g.restored(); assertFalse(g.begin())
    }
}
