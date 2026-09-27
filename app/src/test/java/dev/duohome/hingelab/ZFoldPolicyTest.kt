package dev.duohome.hingelab

import org.junit.Assert.*
import org.junit.Test

class ZFoldPolicyTest {
    @Test fun frozenClosedRepliesNeverWakeInner() {
        val p=ZFoldPolicy()
        repeat(100) { assertNull(p.sample(1f,it*25L,false)) }
        assertEquals(0,p.active)
    }
    @Test fun openingUsesThreeDegreesThenReleasesAtNinety() {
        val p=ZFoldPolicy()
        assertNull(p.sample(1f,0,false))
        assertNull(p.sample(2f,25,false))
        assertEquals("OPEN",p.sample(3f,50,false))
        assertNull(p.sample(60f,100,true))
        assertEquals("RELEASE",p.sample(90f,150,true))
    }
    @Test fun closingRequiresDirectionConfirmationAndPreparesAt150() {
        val p=ZFoldPolicy()
        assertNull(p.sample(180f,0,true))
        assertNull(p.sample(160f,25,true))
        assertEquals("CLOSE",p.sample(150f,120,true))
        p.closed()
        assertEquals(0,p.active)
    }
    @Test fun cachedElevenDegreesDoesNotPretendToBeOpening() {
        val p=ZFoldPolicy()
        repeat(40) { assertNull(p.sample(11f,it*25L,false)) }
    }
    @Test fun detectsBothSamsungUnsubscribeFormatsButNotRegistration() {
        assertTrue(ZFoldLog.unsubscribed("I/SprWallpaper|FoldInteractive: unregisterSensor: mIsSensorRegistered[true]"))
        assertTrue(ZFoldLog.unsubscribed("I/SprWallpaper|FoldInteractive: unregisterSensor: mIsSensorRegistered=true"))
        assertFalse(ZFoldLog.unsubscribed("I/SprWallpaper|FoldInteractive: unregisterSensor: mIsSensorRegistered=false"))
        assertFalse(ZFoldLog.unsubscribed("I/SprWallpaper|FoldInteractive: registerSensor: mIsSensorRegistered=true"))
        assertFalse(ZFoldLog.unsubscribed("OtherService: unregisterSensor: mIsSensorRegistered=true"))
    }
    @Test fun stationaryCloseReleasesOnceAndDoesNotRecreateOnCachedReplies() {
        val p=ZFoldPolicy()
        p.sample(180f,0,true)
        p.sample(160f,25,true)
        assertEquals("CLOSE",p.sample(150f,120,true))
        assertNull(p.tick(1119))
        assertEquals("STATIONARY",p.tick(1120))
        assertEquals(0,p.active)
        repeat(20) {
            assertNull(p.sample(150f,1150L+it*25,true))
            assertNull(p.tick(1150L+it*25))
        }
        assertEquals("CLOSE",p.sample(145f,1800,true))
    }
    @Test fun handoffDoesNotStartAnotherTransitionBeforeCompletion() {
        val p=ZFoldPolicy()
        p.sample(180f,0,true); p.sample(160f,25,true); p.sample(150f,120,true)
        p.beginHandoff()
        assertNull(p.sample(1f,200,false))
        assertNull(p.tick(2000))
        assertEquals(2,p.active)
        p.closed()
        assertEquals(0,p.active)
    }
    @Test fun tinyJitterDoesNotPostponeStationaryTimeout() {
        val p=ZFoldPolicy()
        p.sample(180f,0,true); p.sample(160f,25,true); p.sample(150f,120,true)
        p.sample(149f,700,true)
        assertEquals("STATIONARY",p.tick(1120))
    }
}
