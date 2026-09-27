package dev.duohome.hingelab

import org.junit.Assert.*
import org.junit.Test

class TransitionHoldPolicyTest {
    @Test fun staleAndAbsentAnglesCannotHoldPower() {
        assertFalse(TransitionHoldPolicy.closing(true, null, 0))
        assertFalse(TransitionHoldPolicy.closing(true, 1f, 501))
        assertFalse(TransitionHoldPolicy.closing(true, Float.NaN, 0))
        assertFalse(TransitionHoldPolicy.closing(true, 1f, -1))
        assertFalse(TransitionHoldPolicy.closing(false, 1f, 0))
    }
    @Test fun closingThresholdHasSeparateRearmBoundary() {
        assertTrue(TransitionHoldPolicy.closing(true, 15f, 500))
        assertFalse(TransitionHoldPolicy.closing(true, 16f, 0))
        assertFalse(TransitionHoldPolicy.rearm(30f, 0))
        assertTrue(TransitionHoldPolicy.rearm(31f, 0))
        assertFalse(TransitionHoldPolicy.rearm(180f, 501))
    }
}
