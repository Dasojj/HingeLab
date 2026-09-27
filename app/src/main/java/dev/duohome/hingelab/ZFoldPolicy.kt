package dev.duohome.hingelab

import dev.duohome.hingelab.effect.HingeTravel
import dev.duohome.hingelab.effect.HingeTravelEstimator
import kotlin.math.abs

/** Sensor/display decisions from ZFoldDuo; rendering remains the Lab's own test scene. */
class ZFoldPolicy {
    private val motion=HingeTravelEstimator()
    private var previous=Float.NaN
    private var armed=false
    private var stationaryAnchor=Float.NaN
    private var stationarySince=0L
    private var stationarySuppressed=false
    private var handingOff=false
    var active=0 // 0 idle, 1 opening, 2 closing
        private set
    fun sample(angle: Float, now: Long, inner: Boolean): String? {
        if(!angle.isFinite() || angle !in 0f..180f) return null
        val before=previous; previous=angle
        // HingeOverlayService.trackStationary: do not recreate a transition on
        // repeated cached replies. This intentionally does not reset motion.
        if(!stationaryAnchor.isFinite() || abs(angle-stationaryAnchor)>1.5f) {
            stationaryAnchor=angle; stationarySince=now; stationarySuppressed=false
        }
        if(stationarySuppressed) return null
        if(handingOff) return null
        val travel=motion.update(angle,now)
        if(angle<=2f) armed=true
        if(active==0 && armed && !inner && before.isFinite() && angle>=3f && angle>before) {
            armed=false; active=1; return "OPEN"
        }
        if(active==1 && (angle>=90f || (angle<=2f && before.isFinite() && angle<before))) {
            active=0; return "RELEASE"
        }
        if(active==2 && travel==HingeTravel.OPENING && angle>=170f) {
            active=0; return "RELEASE"
        }
        if(active==0 && inner && travel==HingeTravel.CLOSING && angle in 1.5f..150f) {
            active=2; return "CLOSE"
        }
        return null
    }
    fun tick(now: Long): String? {
        if(!handingOff && !stationarySuppressed && stationaryAnchor.isFinite() && now-stationarySince>=1000L) {
            stationarySuppressed=true
            val hadTransition=active!=0
            active=0
            return if(hadTransition) "STATIONARY" else null
        }
        return null
    }
    fun beginHandoff() { handingOff=true }
    fun closed() { handingOff=false; active=0; motion.force(HingeTravel.CLOSING,previous) }
}
