// ZFoldDuo, copyright (c) 2026 nnnnnnn0090, MIT; assets/ZFoldDuo-MIT.txt.
package dev.duohome.hingelab.effect

data class EffectProfile(
    val intensity: Float = 1f,
    val blurSpread: Float = 0.12f,
    val eyeDistanceMm: Float = 450f,
    val foldSplitsLong: Boolean = true,
    val movingSide: Int = -1,
    val coverFrostFromRight: Boolean = true,
    val panelSwitchAngle: Float = 20f,
    val boundedInner: Boolean = false,
    val softInner: Boolean = false,
    val perspectiveWindow: Boolean = false,
)
