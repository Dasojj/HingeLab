package dev.duohome.hingelab
import org.junit.Assert.*
import org.junit.Test

class ForegroundEventPolicyTest {
    @Test fun secondaryWindowDoesNotEvictCoverFrame() {
        assertFalse(ForegroundEventPolicy.establishesOwner(1,true))
    }
    @Test fun popupIsNotAnAppSwitch() {
        assertFalse(ForegroundEventPolicy.establishesOwner(0,false))
        assertFalse(ForegroundEventPolicy.establishesOwner(-1,true))
    }
    @Test fun primaryApplicationChangeIsRecognized() {
        assertTrue(ForegroundEventPolicy.establishesOwner(0,true))
    }
}
