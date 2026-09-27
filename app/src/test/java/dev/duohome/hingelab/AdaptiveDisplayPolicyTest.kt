package dev.duohome.hingelab

import org.junit.Assert.*
import org.junit.Test

class AdaptiveDisplayPolicyTest {
    @Test fun earlierTransferThresholdPreservesFreshnessAndOriginalMode() {
        assertTrue(AdaptiveDisplayPolicy.shouldEnter(3,160f,0,true))
        assertFalse(AdaptiveDisplayPolicy.shouldEnter(3,161f,0,true))
        assertFalse(AdaptiveDisplayPolicy.shouldEnter(3,160f,0,false))
        assertFalse(AdaptiveDisplayPolicy.shouldEnter(3,160f,501,true))
        assertFalse(AdaptiveDisplayPolicy.shouldEnter(3,181f,0,true))
    }

    @Test fun earlyOpeningDoesNotNeedPrivateAngle() {
        assertTrue(AdaptiveDisplayPolicy.shouldEnter(1, null, Long.MAX_VALUE))
        assertFalse(AdaptiveDisplayPolicy.shouldEnter(0, 30f, 0))
    }
    @Test fun staleOrInvalidAnglesCannotSwitchOpenDisplay() {
        for (angle in listOf<Float?>(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 181f)) {
            assertFalse(AdaptiveDisplayPolicy.shouldEnter(3, angle, 0))
            assertFalse(AdaptiveDisplayPolicy.shouldRelease(3, angle, 0))
        }
        assertFalse(AdaptiveDisplayPolicy.shouldEnter(3, 100f, 501))
        assertFalse(AdaptiveDisplayPolicy.shouldRelease(3, 180f, 501))
    }
    @Test fun fullyClosedAlwaysReleasesWithoutAngle() {
        assertTrue(AdaptiveDisplayPolicy.shouldRelease(0, null, Long.MAX_VALUE))
    }
    @Test fun separatedThresholdsPreventReentryAtFullyOpen() {
        assertTrue(AdaptiveDisplayPolicy.shouldEnter(3, 150f, 0))
        assertFalse(AdaptiveDisplayPolicy.shouldEnter(3, 178f, 0))
        assertTrue(AdaptiveDisplayPolicy.shouldRelease(3, 178f, 0))
        assertFalse(AdaptiveDisplayPolicy.shouldRelease(3, 150f, 0))
    }
    @Test fun cachedOpenAngleCannotImmediatelyEndTentTransition() {
        assertFalse(AdaptiveDisplayPolicy.shouldRelease(1, 180f, 0))
        assertFalse(AdaptiveDisplayPolicy.shouldRelease(2, 180f, 0))
    }
}
