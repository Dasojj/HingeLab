package dev.duohome.hingelab.effect

import android.graphics.Bitmap

/**
 * Demo adaptation of Compat's preserved cover snapshot + FrameSmoother handoff.
 * Frames belong to physical panels, not logical display IDs (which Samsung swaps).
 * No capture of other apps: the caller supplies Lab's generated desktop.
 */
class CompatPanelFrames(private val render: (Boolean, Float) -> Unit) {
    private val images = mutableMapOf<Boolean, Bitmap>()
    private val inner = FrameSmoother { render(true, it) }.also { it.snap(HingeProjection.MAX_TILT) }
    private val cover = FrameSmoother { render(false, it) }.also { it.snap(0f) }

    fun image(panel: Boolean, width: Int, height: Int, generate: (Int, Int) -> Bitmap): Bitmap {
        val previous = images[panel]
        // Retain the actual source while the system changes surfaces/sizes.
        // A rotation is a new composition, not a display handoff.
        if (previous != null && (previous.width > previous.height) == (width > height)) return previous
        return generate(width, height).also { images[panel] = it }
    }

    fun tilt(panel: Boolean) = if (panel) inner.current else cover.current

    fun follow(angle: Float, fresh: Boolean, config: EffectProfile) {
        if (!fresh) {
            inner.snap(0f); cover.snap(0f)
            render(true, 0f); render(false, 0f)
            return
        }
        // Like Compat, smooth the projected planes themselves. Smoothing the
        // hinge before clamping its projection delays the visible cover motion.
        inner.setTarget(HingeProjection.tiltForHinge(angle, config))
        cover.setTarget(HingeProjection.coverTiltForHinge(angle, config))
    }

    fun stop() { inner.cancel(); cover.cancel(); images.clear() }
}
