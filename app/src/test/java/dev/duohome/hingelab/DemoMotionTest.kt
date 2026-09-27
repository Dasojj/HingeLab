package dev.duohome.hingelab
import org.junit.Assert.*
import org.junit.Test
class DemoMotionTest {
    @Test fun softProjectionMovesAcrossWholeRangeAndSettlesBeforeJitter() {
        val tilts=(55..178).map { DemoMotion.softInnerTilt(it.toFloat()) }
        assertTrue(tilts.zipWithNext().all { (a,b) -> b<a })
        assertEquals(0f,DemoMotion.softInnerTilt(178f),0f)
        assertEquals(0f,DemoMotion.softInnerTilt(Float.NaN),0f)
        assertTrue(DemoMotion.softInnerTilt(90f)<70f)
        assertTrue(DemoMotion.softInnerTilt(177f)<0.02f)
        assertTrue(tilts.zipWithNext().all { (a,b) -> a-b<1f })
    }
    @Test fun containedProjectionHasNoEdgeOnPlateau() {
        assertEquals(65f,DemoMotion.boundedInnerTilt(55f),0f)
        assertEquals(0f,DemoMotion.boundedInnerTilt(180f),0f)
        val tilts=(55..180).map { DemoMotion.boundedInnerTilt(it.toFloat()) }
        assertTrue(tilts.zipWithNext().all { (a,b) -> b<a })
        assertEquals(0f,DemoMotion.boundedInnerTilt(Float.NaN),0f)
    }
    @Test fun earlyInnerWindowIsBoundedAndReversible() {
        assertEquals(89.5f,DemoMotion.earlyInnerTilt(0f),0f)
        assertEquals(89.5f,DemoMotion.earlyInnerTilt(55f),0f)
        assertEquals(44.75f,DemoMotion.earlyInnerTilt(115f),0.001f)
        assertTrue(DemoMotion.earlyInnerTilt(85f)>0f)
        assertEquals(0f,DemoMotion.earlyInnerTilt(175f),0f)
        assertEquals(0f,DemoMotion.earlyInnerTilt(180f),0f)
        assertEquals(0f,DemoMotion.earlyInnerTilt(Float.NaN),0f)
        val opening=(0..180).map { DemoMotion.earlyInnerTilt(it.toFloat()) }
        assertTrue(opening.zipWithNext().all { (a,b) -> b<=a })
        assertEquals(opening.reversed(),(180 downTo 0).map { DemoMotion.earlyInnerTilt(it.toFloat()) })
    }
    @Test fun flatJitterDoesNotFoldImage() {
        listOf(178f,179f,180f).forEach { assertEquals(180f,DemoMotion.target(it,true,true),0f) }
        assertEquals(177f,DemoMotion.target(177f,true,true),0f)
    }
    @Test fun staleLiveAngleReturnsFlatButManualDemoStillWorks() {
        assertEquals(180f,DemoMotion.target(70f,true,false),0f)
        assertEquals(70f,DemoMotion.target(70f,false,false),0f)
        assertEquals(180f,DemoMotion.target(Float.NaN,true,true),0f)
    }
}
