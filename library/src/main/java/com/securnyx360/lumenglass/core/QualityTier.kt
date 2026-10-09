package com.securnyx360.lumenglass.core

/**
 * Performance tier for the glass effect.
 *
 * Glass rendering is expensive (blur taps + lens sampling + optional dispersion). Rather than a
 * single heavy shader, Lumen Glass selects a tier from the device's capabilities — with an app
 * override — so it runs acceptably on **low-end hardware** (a first-class requirement, not a
 * bolt-on).
 *
 * The tier decides **which effects actually run**; the shader reads a single `tier` uniform.
 *
 * @see resolveFeatures
 */
enum class QualityTier {
    /** Full taps + dispersion + bezel. Flagship GPUs. */
    HIGH,

    /** Separable blur; dispersion optional; no bezel. Mainstream. */
    BALANCED,

    /** Reduced-resolution capture + single-pass small blur + tint only. Low-end / old GPUs. */
    LOW,

    /**
     * No shader (API < 33 or a non-AGSL renderer) → a plain frosted panel.
     * Must look **intentional**, never broken.
     */
    FALLBACK,
}

/**
 * The concrete feature set a tier enables. A backend maps these flags to shader uniforms / paths.
 *
 * @property useShader `false` for [QualityTier.FALLBACK] (draw a frost panel, no AGSL).
 * @property useLens whether the lens displacement runs at all.
 * @property useDispersion whether chromatic dispersion runs (the single most expensive effect).
 * @property useBezel whether the bezel inner-shadow runs.
 * @property useSeparableBlur `true` = two-pass H+V blur (18 taps); `false` = single-pass small blur.
 * @property captureScale linear capture scale `(0, 1]`; `< 1` renders the backdrop smaller
 *   (fewer fragments) — glass is blurry anyway, so the loss is invisible.
 */
data class GlassFeatures(
    val useShader: Boolean,
    val useLens: Boolean,
    val useDispersion: Boolean,
    val useBezel: Boolean,
    val useSeparableBlur: Boolean,
    val captureScale: Float,
)

/**
 * Maps a [QualityTier] to its [GlassFeatures].
 *
 * This is the **single source of truth** for tier behaviour, so the policy is unit-testable and the
 * backend cannot drift from it.
 */
fun QualityTier.resolveFeatures(): GlassFeatures = when (this) {
    QualityTier.HIGH -> GlassFeatures(
        useShader = true,
        useLens = true,
        useDispersion = true,
        useBezel = true,
        useSeparableBlur = true,
        captureScale = 1.0f,
    )

    QualityTier.BALANCED -> GlassFeatures(
        useShader = true,
        useLens = true,
        // Dispersion is the most expensive effect; BALANCED leaves it off by default.
        useDispersion = false,
        useBezel = false,
        useSeparableBlur = true,
        captureScale = 0.75f,
    )

    QualityTier.LOW -> GlassFeatures(
        useShader = true,
        useLens = true,
        useDispersion = false,
        useBezel = false,
        useSeparableBlur = false,
        captureScale = 0.5f,
    )

    QualityTier.FALLBACK -> GlassFeatures(
        useShader = false,
        useLens = false,
        useDispersion = false,
        useBezel = false,
        useSeparableBlur = false,
        captureScale = 0.5f,
    )
}
