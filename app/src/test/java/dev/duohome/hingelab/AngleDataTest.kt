package dev.duohome.hingelab
import org.junit.Assert.*
import org.junit.Test
class AngleDataTest {
    @Test fun parsesActualFold8LogAndIntermediateValuesWithoutRounding() {
        val actual = "I/SprWallpaper|FoldInteractive( 4383): onCommand: action=hingelab_angle, mCurrentAngle=180.0, isVisible=false"
        assertEquals(180f, AngleData.parse(actual))
        assertEquals(43.625f, AngleData.parse(actual.replace("180.0", "43.625")))
        assertEquals(0f, AngleData.parse(actual.replace("180.0", "0.0")))
        assertNull(AngleData.parse(actual.replace("hingelab_angle", "hingelab_angle_other")))
        assertNull(AngleData.parse(actual.replace("180.0", "180.0garbage")))
        assertNull(AngleData.parse(actual.replace("180.0", "-1")))
        assertNull(AngleData.parse(actual.replace("180.0", "NaN")))
        assertNull(AngleData.parse(actual.replace("180.0", "1e99")))
    }
    @Test fun acceptsFractionalButNotUnrelatedOrInvalidTelemetry() {
        assertEquals(43.625f, AngleData.parse("I/SprWallpaper|FoldInteractive: onCommand: action[hingelab_angle], mCurrentAngle[43.625]"))
        assertNull(AngleData.parse("onCommand: action[other], mCurrentAngle[90]"))
        assertNull(AngleData.parse("onCommand: action[hingelab_angle], mCurrentAngle[181]"))
        assertNull(AngleData.parse("onCommand: action[hingelab_angle], mCurrentAngle[NaN]"))
    }
    @Test fun missingAndStaleSamplesAreNotCurrent() {
        assertFalse(AngleData.fresh(0, 100))
        assertFalse(AngleData.fresh(100, 1700))
        assertTrue(AngleData.fresh(100, 1600))
    }
}
