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
 * @property cornerRadiusDp corner radius of the glass surface in dp; `0f` = sharp rectangle.
 * @property precisionMode when true, activates Apple-calibrated dual-lobe hairline highlights,
 *   asymmetric directional lighting, and normal-aligned rim bend. When false, runs legacy diffuse mode.
 * @property rimProfile geometric curvature profile for the rim bend. Defaults to [RimProfile.TORIC].
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
    val precisionMode: Boolean = true,
    val rimProfile: RimProfile = RimProfile.TORIC,
) {
    companion object {
        /** A neutral, effect-light default suitable for previews / fallbacks. */
        val Default: GlassSpec = GlassSpec()

        /**
         * Apple-calibrated Light glass preset matching iOS control sheets and navigation bars
         * (as captured in Image.png). High frost, translucent white tint, razor hairline top rim.
         */
        val AppleLight: GlassSpec = GlassSpec(
            lens = LensModel.Blend(centre = 0.05f, rim = 0.50f),
            refraction = 0.9f,
            dispersion = 0.05f,
            frostDp = 6f,
            tintArgb = 0x88FFFFFF.toInt(),
            saturation = 1.1f,
            contrast = 1.0f,
            edgeLight = 0.28f,
            bezel = Bezel(radiusDp = 8f, alpha = 0.08f),
            precisionMode = true,
            rimProfile = RimProfile.TORIC,
        )

        /**
         * Apple-calibrated Dark glass preset matching iOS / visionOS dark mode sheets and dock
         * (as captured in Image (1).png). Smoky slate tint with high-contrast top specular stroke.
         */
        val AppleDark: GlassSpec = GlassSpec(
            lens = LensModel.Blend(centre = 0.05f, rim = 0.55f),
            refraction = 1.0f,
            dispersion = 0.08f,
            frostDp = 8f,
            tintArgb = 0xA01C1D22.toInt(),
            saturation = 1.15f,
            contrast = 1.05f,
            edgeLight = 0.35f,
            bezel = Bezel(radiusDp = 10f, alpha = 0.16f),
            precisionMode = true,
            rimProfile = RimProfile.TORIC,
        )
    }
}

/**
 * Geometric curvature profiles for edge refraction (rim bend).
 */
enum class RimProfile {
    /**
     * Pattern A: Toric Meniscus (Apple Watch / visionOS look).
     * Circular arc cross-section (`sqrt(1 - t^2)`) producing physical rim magnification
     * and inward perpendicular refraction along the boundary normal.
     */
    TORIC,

    /**
     * Pattern B: Quintic Hermite Bevel (iOS Control Center look).
     * Smooth polynomial curve with soft progressive magnification.
     */
    SMOOTH,

    /**
     * Pattern C: Liquid Droplet (macOS / Fluid Glass look).
     * Bipolar droplet meniscus producing dual-zone fluid refraction.
     */
    DROPLET,

    /** Legacy squircle displacement field. */
    LEGACY,
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
