package com.subhankar.lumenglass.core

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
 * @property useBlur whether the **platform blur** runs.
 *
 *   **Naming note (P3.5):** this was `useSeparableBlur`, from an earlier plan that assumed we would
 *   hand-write a two-pass H+V blur in AGSL. Investigation showed the platform already provides
 *   that: `RenderEffect.createBlurEffect(radiusX, radiusY, input, edgeTreatment)` takes independent
 *   X and Y radii, i.e. the framework's blur is already separable and hardware-accelerated. Writing
 *   our own tap loop would be strictly worse — more shader cost, another untested path, and a
 *   re-implementation of something the platform does better. The flag was renamed rather than left
 *   describing something we deliberately do not do.
 *
 *   `true` = apply the platform blur; `false` = no blur (tint only). See `PLAN.md` §5.2.
 * @property captureScale linear capture scale `(0, 1]`; `< 1` renders the backdrop smaller
 *   (fewer fragments) — glass is blurry anyway, so the loss is invisible. This is now the primary
 *   source of cost reduction, since blur is cheap and handled by the platform.
 */
data class GlassFeatures(
    val useShader: Boolean,
    val useLens: Boolean,
    val useDispersion: Boolean,
    val useBezel: Boolean,
    val useBlur: Boolean,
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
        useBlur = true,
        captureScale = 1.0f,
    )

    QualityTier.BALANCED -> GlassFeatures(
        useShader = true,
        useLens = true,
        // Dispersion is the most expensive effect; BALANCED leaves it off by default.
        useDispersion = false,
        useBezel = false,
        useBlur = true,
        captureScale = 0.75f,
    )

    QualityTier.LOW -> GlassFeatures(
        useShader = true,
        useLens = true,
        useDispersion = false,
        useBezel = false,
        useBlur = false,
        captureScale = 0.5f,
    )

    QualityTier.FALLBACK -> GlassFeatures(
        useShader = false,
        useLens = false,
        useDispersion = false,
        useBezel = false,
        useBlur = false,
        captureScale = 0.5f,
    )
}

/**
 * The minimum API level at which AGSL (`RuntimeShader`) exists — Android 13 / Tiramisu.
 *
 * Mirrored here (rather than imported from `android/`) so `core/` stays platform-agnostic. The
 * `android/` layer has its own constant for the same value; `QualityTierTest` asserts they agree so
 * the two cannot silently diverge.
 */
const val AGSL_MIN_API_LEVEL: Int = 33

/**
 * Picks a default [QualityTier] from a device's capabilities.
 *
 * Pure: the caller supplies the API level, so this is JVM-testable and the policy is not spread
 * across the `android/` layer.
 *
 * ## The detectability contract (this is the point of the phase)
 *
 * `PLAN.md` §5.2: *"Never silently drop below the requested look without the app being able to
 * detect it."* Three consequences, all deliberate:
 *
 * 1. This function returns a **tier**, which the caller resolves to [GlassFeatures]. A caller can
 *    therefore *ask* what it actually got ("did I get dispersion?") rather than infer it.
 * 2. **This probe only ever returns HIGH, BALANCED or FALLBACK — never LOW.** Choosing LOW is a
 *    judgement about *performance*, and a pure capability probe cannot know whether the device is
 *    slow. Silently estimating "this looks like a low-end GPU" from an API level would be exactly
 *    the invisible downgrade the contract forbids. LOW is therefore an explicit override
 *    (battery saver, a user setting, a benchmark run) or a measured decision made later.
 * 3. **An override is honoured only up to what the device can actually do.** A request for HIGH on
 *    a device without AGSL cannot be satisfied; returning `HIGH` there would produce features
 *    claiming `useShader = true` for a surface that can only draw a frost panel — a lie that is
 *    *not* detectable downstream, because the features look correct. So the request is clamped to
 *    the device ceiling. This is still app-detectable: the caller compares the returned tier
 *    against the one it requested, and the resolved features reflect reality.
 *
 * The heuristic here is only the one thing genuinely knowable up front: whether AGSL exists at all.
 * Everything else is deliberately left to the override / measured path.
 *
 * @param apiLevel the device's `Build.VERSION.SDK_INT`.
 * @param requestedTier an explicit override; when non-null it wins **unless the device cannot run
 *   it**, in which case it is clamped (see point 3 above).
 * @return the tier to use.
 */
fun selectTier(apiLevel: Int, requestedTier: QualityTier? = null): QualityTier {
    val ceiling = if (apiLevel >= AGSL_MIN_API_LEVEL) QualityTier.HIGH else QualityTier.FALLBACK
    if (requestedTier == null) {
        // Default: run the shader at a mainstream cost profile when we can, else degrade.
        return if (apiLevel >= AGSL_MIN_API_LEVEL) QualityTier.BALANCED else QualityTier.FALLBACK
    }
    return clampToCeiling(requestedTier, ceiling)
}

/**
 * Clamps a requested tier to what [ceiling] can actually run.
 *
 * Ranking is by *capability*, not by cost: FALLBACK \< LOW \< BALANCED \< HIGH is the cost order,
 * but FALLBACK is the only tier that is genuinely *less capable* (no shader at all). LOW, BALANCED
 * and HIGH all run the shader and differ only in quality, so any of them is satisfiable on an
 * AGSL-capable device.
 *
 * On a non-AGSL device the ceiling is [QualityTier.FALLBACK], and nothing above it can be honoured.
 */
private fun clampToCeiling(requested: QualityTier, ceiling: QualityTier): QualityTier =
    if (ceiling == QualityTier.FALLBACK && requested != QualityTier.FALLBACK) {
        QualityTier.FALLBACK
    } else {
        requested
    }
