package dev.duohome.hingelab
import org.junit.Assert.*
import org.junit.Test
class ForegroundVerificationTest {
    @Test fun staleHomeEventPreservesRealApplication() { assertEquals(0,ForegroundVerification.decide("messages","home","messages")) }
    @Test fun realHomeNavigationIsRespected() { assertEquals(1,ForegroundVerification.decide("home","home","messages")) }
    @Test fun missingOrDifferentFocusDoesNotShowOldContent() {
        assertEquals(2,ForegroundVerification.decide(null,"home","messages"))
        assertEquals(2,ForegroundVerification.decide("other","home","messages"))
    }
}
