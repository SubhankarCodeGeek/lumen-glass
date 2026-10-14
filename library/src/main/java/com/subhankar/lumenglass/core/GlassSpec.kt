package com.subhankar.lumenglass.core

/**
 * The optical configuration of a glass surface — the v1 effect set.
 *
 * All values are **typed** and have sane defaults; consumers configure a `GlassSpec` rather than
 * poking raw shader uniforms (which is the crash-prone pattern this library is designed to avoid).
 *
 * This type is pure data (no graphics types) and lives in `core/` so it is KMP-shareable and
 * JVM-unit-testable.
 *
 * @property lens which lens model to use (centre / rim / blend) — the differentiator.
 * @property refraction overall displacement strength.
 * @property dispersion chromatic-aberration amount; `0f` disables it (and the tier may disable it
 *   regardless).
 * @property frostDp blur radius in dp; `0f` = no blur.
 * @property tintArgb material tint colour, ARGB (`0` = fully transparent = no tint). Expects its
 *   own alpha; the shader blends it over the lensed content.
 * @property saturation colour saturation multiplier (`1f` = unchanged).
 * @property contrast colour contrast multiplier (`1f` = unchanged).
 * @property edgeLight specular edge-band strength; `0f` disables it.
 * @property bezel optional bezel inner-shadow; `null` = none.
 */
data class GlassSpec(
    val lens: LensModel = LensModel.Blend(centre = 0.6f, rim = 0.4f),
    val refraction: Float = 1.0f,
    val dispersion: Float = 0f,
    val frostDp: Float = 6f,
    val tintArgb: Int = 0,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val edgeLight: Float = 0.1f,
    val bezel: Bezel? = null,
    val cornerRadiusDp: Float = 0f,
) {
    companion object {
        /** A neutral, effect-light default suitable for previews / fallbacks. */
        val Default: GlassSpec = GlassSpec()
    }
}

/**
 * Optional bezel — a soft dark inner edge that gives the glass depth.
 *
 * @property radiusDp blur radius of the inner shadow.
 * @property offsetYDp vertical offset of the inner shadow.
 * @property alpha shadow alpha (`0f`..`1f`).
 */
data class Bezel(
    val radiusDp: Float = 12f,
    val offsetYDp: Float = 4f,
    val alpha: Float = 0.12f,
) {
    companion object {
        /** A subtle default bezel. */
        fun soft(radiusDp: Float = 12f, alpha: Float = 0.12f): Bezel =
            Bezel(radiusDp = radiusDp, alpha = alpha)
    }
}
