package dev.duohome.hingelab
import org.junit.Assert.*
import org.junit.Test
class FallbackPolicyTest {
    @Test fun nativeCoverAlwaysWins() {
        for(mode in 0..2) assertFalse(FallbackPolicy.allowed(mode,true,true,true,1))
    }
    @Test fun originalModeNeverUsesFallback() {
        assertFalse(FallbackPolicy.allowed(0,false,true,true,1))
    }
    @Test fun onlyMissingCoverDuringClosingCanUseEitherExperiment() {
        for(mode in 1..2) {
            assertTrue(FallbackPolicy.allowed(mode,false,true,true,1))
            assertFalse(FallbackPolicy.allowed(mode,false,false,true,1))
            assertFalse(FallbackPolicy.allowed(mode,false,true,false,1))
            assertFalse(FallbackPolicy.allowed(mode,false,true,true,0))
        }
    }
}
