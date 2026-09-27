package dev.duohome.hingelab
import org.junit.Assert.*
import org.junit.Test

class ReadinessTest {
    @Test fun unicodeWifiLabelsMatchButUsbNeverDoes() {
        listOf("Отладка по Wi-Fi", "Отладка по Wi‑Fi", "Отладка по Wi–Fi", "  WIRELESS DEBUGGING ", "Беспроводная отладка").forEach { assertTrue(it,SetupLabels.wireless(it)) }
        assertFalse(SetupLabels.wireless("Отладка по USB"))
        assertFalse(SetupLabels.wireless("Не включайте отладку по Wi-Fi"))
    }
    @Test fun repeatedRepliesAndThreeStateSensorDoNotProvePreciseMotion() {
        val ready=Readiness(); repeat(100) { ready.sample(180f) }; ready.sample(0f); ready.sample(90f)
        assertFalse(ready.motionVerified)
        ready.sample(74f); ready.sample(78f); ready.sample(81f)
        assertTrue(ready.motionVerified)
    }
    @Test fun nonceCommandAcceptedButOtherCommandsRejected() {
        assertEquals(72f,AngleData.parse("onCommand: action=hingelab_angle_abc123, mCurrentAngle=72.0, isVisible=true"))
        assertNull(AngleData.parse("onCommand: action=hingelab_angle_abc123evil, mCurrentAngle=72.0"))
    }
    @Test fun backgroundDoesNotInheritForegroundMovement() {
        val audit=ContextAudit(); listOf(50f,60f,70f).forEach { audit.observe("foreground",it) }
        repeat(10) { audit.observe("background",70f) }
        assertTrue(audit.report().contains("background: связь 0 сигналов, 10 ответов, 1 значений · изменение НЕ подтверждено"))
    }
    @Test fun heartbeatWithoutSamplesDoesNotProveSensorMovement() {
        val audit=ContextAudit()
        repeat(20) { audit.heartbeat("locked") }
        assertTrue(audit.report().contains("locked: связь 20 сигналов, 0 ответов, 0 значений · изменение НЕ подтверждено"))
    }
    @Test fun shellSourceDoesNotProveFreshAngle() {
        val ready=Readiness(); ready.health("connected"); ready.health("source=shell"); ready.health("heartbeat")
        assertTrue(ready.shellRequests)
        assertFalse(ready.motionVerified)
        assertTrue(ready.summary(false).contains("свежих ответов нет"))
    }
}
