package dev.duohome.hingelab

/** Only fresh data controls the fold; near-flat sensor jitter leaves a flat screen. */
object DemoMotion {
    fun softInnerTilt(angle: Float): Float {
        if(!angle.isFinite()) return 0f
        val p=((angle-55f)/123f).coerceIn(0f,1f)
        return 78f*(1f-p*p*(3f-2f*p))
    }
    fun boundedInnerTilt(angle: Float): Float {
        if(!angle.isFinite()) return 0f
        val p=((angle-55f)/125f).coerceIn(0f,1f)
        return 65f*(1f-p*p*(3f-2f*p))
    }
    /** Visual remapping only. Never used as a sensor value or display-state trigger. */
    fun earlyInnerTilt(angle: Float): Float {
        if (!angle.isFinite()) return 0f
        val progress = ((angle - 55f) / 120f).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        return 89.5f * (1f - eased)
    }

    fun target(angle: Float, live: Boolean, fresh: Boolean): Float = when {
        live && !fresh -> 180f
        !angle.isFinite() -> 180f
        angle >= 178f -> 180f
        else -> angle.coerceIn(0f,180f)
    }
}
