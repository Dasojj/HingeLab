package dev.duohome.hingelab
import org.junit.Assert.*
import org.junit.Test
class DirectAngleDataTest {
    @Test fun subscriptionAndErrorsAreNotMeasurements() {
        listOf("DIRECT subscribed","DIRECT failed:SecurityException","DIRECT_ANGLE NaN","DIRECT_ANGLE Infinity","DIRECT_ANGLE -1","DIRECT_ANGLE 181","DIRECT_ANGLE 20 noise").forEach {
            assertNull(it,DirectAngleData.parse(it))
        }
    }
    @Test fun acceptsRealAnglesIncludingClosedAndIntermediate() {
        assertEquals(0f,DirectAngleData.parse("DIRECT_ANGLE 0.0"))
        assertEquals(15.5f,DirectAngleData.parse("DIRECT_ANGLE 15.5"))
        assertEquals(180f,DirectAngleData.parse("DIRECT_ANGLE 180.0"))
    }
}
