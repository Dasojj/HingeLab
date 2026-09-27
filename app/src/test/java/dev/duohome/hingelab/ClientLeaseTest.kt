package dev.duohome.hingelab

import org.junit.Assert.*
import org.junit.Test

class ClientLeaseTest {
    @Test fun stalledClientExpiresEvenWhenTheSessionHasOnlyJustStarted() {
        val lease=ClientLease(100)
        assertTrue(lease.valid(5099))
        assertFalse(lease.valid(5100))
    }
    @Test fun liveClientCanContinuePastTheOldSixtySecondDeadline() {
        val lease=ClientLease(100)
        for(now in 100L..120100L step 1000) { lease.renew(now); assertTrue(lease.valid(now+999)) }
        assertFalse(lease.valid(125100))
    }
    @Test fun clockBeforeHeartbeatFailsClosed() {
        val lease=ClientLease(100)
        assertFalse(lease.valid(99))
    }
}
