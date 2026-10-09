package com.subhankar.lumenglass.android

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.resolveWeights

/**
 * The resolved uniform values the clean-room [LUMEN_GLASS_AGSL] shader runs with.
 *
 * This exists so there is **exactly one** place that binds uniforms ([buildGlassShader]). A second
 * bind site is a second place to forget a uniform, which is the `unknown identifier` crash class
 * guarded by `LumenGlassShaderStructureTest` (same source set tree).
 *
 * All values are already in shader space (px, ARGB, plain floats) — no dp/Color conversion happens
 * here; that belongs to the caller/Compose layer.
 *
 * ## API status: PROVISIONAL (pre-P4)
 *
 * Public so the P1.7 harness module can drive it. This is **not** the designed public API — that is
 * P4 (`Modifier.lumenGlass(...)` / `rememberLumenGlassState()`). Expect these names and shapes to
 * change. See `ARCHITECTURE.md` §6.
 *
 * ## API status: PROVISIONAL
 *
 * Public because the instrumented tests in `:app` build fixtures with it (see
 * [LumenGlassTestApi]). It is not part of the consumer-facing API — that is the `compose/`
 * package. Treat this type as unstable.
 *
 * @property widthPx surface width in px.
 * @property heightPx surface height in px.
 * @property cornerRadiusPx rounded-corner radius in px.
 * @property centreWeight whole-face lens weight, `0f`..`1f`.
 * @property rimWeight rim lens weight, `0f`..`1f`.
 * @property squircleExp superellipse exponent (e.g. `8f`).
 * @property refraction overall displacement strength.
 * @property dispersion chromatic-aberration amount; `0f` disables it.
 * @property tintArgb material tint, ARGB (expects its own alpha).
 * @property saturation colour saturation multiplier (`1f` = unchanged).
 * @property contrast colour contrast multiplier (`1f` = unchanged).
 * @property edgeLight specular edge-band strength.
 * @property tier selector: `2f` = HIGH (dispersion path), `1f` = BALANCED/LOW.
 * @property bezelAlpha strength of the inner-shadow bezel, `0f`..`1f` (`0f` disables it, which is
 *   what the cheaper tiers pass). Gated on the HIGH tier in the shader.
 * @property bezelRadiusPx width of the bezel band, inward from the border, in px.
 */
data class GlassUniforms(
    val widthPx: Float,
    val heightPx: Float,
    val cornerRadiusPx: Float,
    val centreWeight: Float,
    val rimWeight: Float,
    val squircleExp: Float = DEFAULT_SQUIRCLE_EXP,
    val refraction: Float,
    val dispersion: Float,
    val tintArgb: Int,
    val saturation: Float,
    val contrast: Float,
    val edgeLight: Float,
    val tier: Float,
    val bezelAlpha: Float = 0f,
    val bezelRadiusPx: Float = 0f,
) {
    companion object {
        /** Superellipse exponent matching the reference squircle field. */
        const val DEFAULT_SQUIRCLE_EXP: Float = 8f

        /**
         * Builds [GlassUniforms] from a [GlassSpec], resolving the lens model into weights.
         *
         * This is the model → uniform contract described in ARCHITECTURE §3.1: the shader never
         * sees a [com.subhankar.lumenglass.core.LensModel], only the resolved
         * `centreWeight`/`rimWeight` pair.
         *
         * @param dispersion the amount to actually run. Callers pass `0f` when the tier disables
         *   dispersion, so the shader's cheap path is taken regardless of what the spec requests.
         */
        fun from(
            spec: GlassSpec,
            widthPx: Float,
            heightPx: Float,
            cornerRadiusPx: Float,
            dispersion: Float = spec.dispersion,
            refraction: Float = spec.refraction,
            tier: Float = TIER_BALANCED,
            /** Bezel band width in px, converted by the caller (the spec stores dp). */
            bezelRadiusPx: Float = 0f,
        ): GlassUniforms {
            val weights = spec.lens.resolveWeights()
            return GlassUniforms(
                widthPx = widthPx,
                heightPx = heightPx,
                cornerRadiusPx = cornerRadiusPx,
                centreWeight = weights.centre,
                rimWeight = weights.rim,
                refraction = refraction,
                dispersion = dispersion,
                tintArgb = spec.tintArgb,
                saturation = spec.saturation,
                contrast = spec.contrast,
                edgeLight = spec.edgeLight,
                tier = tier,
                // The bezel is only rendered on the HIGH tier; passing 0 elsewhere is what makes it
                // a no-op rather than an untested branch.
                bezelAlpha = if (tier >= TIER_HIGH) spec.bezel?.alpha ?: 0f else 0f,
                bezelRadiusPx = if (tier >= TIER_HIGH) bezelRadiusPx else 0f,
            )
        }
    }
}

/** Tier uniform value meaning "HIGH — the dispersion code path is available". */
internal const val TIER_HIGH: Float = 2f

/** Tier uniform value meaning "BALANCED / LOW — single content sample, no dispersion". */
internal const val TIER_BALANCED: Float = 1f

/**
 * The minimum API level at which AGSL (`RuntimeShader`) exists.
 *
 * Below this the library degrades to the FALLBACK tier (a frosted panel, no shader) — see
 * `QualityTier.FALLBACK`. Declared here so there is one constant rather than scattered `33`s.
 */
internal const val AGSL_MIN_API: Int = Build.VERSION_CODES.TIRAMISU

/**
 * The minimum API level for [android.graphics.RenderNode] — the capture primitive.
 *
 * Lower than [AGSL_MIN_API], but declared separately because they are genuinely different gates:
 * a device can have `RenderNode` without AGSL. The library uses the *higher* of the two to decide
 * whether the shader path can run at all.
 */
internal const val RENDER_NODE_MIN_API: Int = Build.VERSION_CODES.Q

/**
 * The minimum API level for attaching a `RenderEffect` to a `RenderNode`.
 *
 * **Higher than [RENDER_NODE_MIN_API]**: the node itself is API 29, but
 * `RenderNode.setRenderEffect` is API 31. The distinction matters — a device can have `RenderNode`
 * without being able to apply an effect to it, so the two gates are genuinely different.
 *
 * Still below [AGSL_MIN_API] (33), so in practice AGSL is the binding constraint for the glass
 * path; this constant exists so the layering is explicit rather than accidentally correct.
 */
internal const val RENDER_EFFECT_MIN_API: Int = Build.VERSION_CODES.S

/**
 * Whether this device can run the AGSL shader at all.
 *
 * `false` on API < 33. Callers use this to choose the FALLBACK tier rather than attempting a
 * shader build that would fail.
 */
@ChecksSdkIntAtLeast(api = AGSL_MIN_API)
internal fun isAgsSupported(): Boolean = Build.VERSION.SDK_INT >= AGSL_MIN_API

/**
 * Builds a fully-bound [RuntimeShader] for the Lumen Glass material.
 *
 * Returns `null` below API 33 (no AGSL). Callers **must** handle `null` by drawing the FALLBACK
 * frost panel — this is the graceful-degradation contract, and it is never silent: the caller is
 * handed `null` rather than a shader that would throw at draw time.
 *
 * Every uniform in [LUMEN_GLASS_UNIFORMS] is bound here, and only here — the structural test
 * asserts the shader declares them all, so a rename in either place fails the build's tests rather
 * than crashing a consumer's device.
 *
 * @param uniforms the resolved values to bind.
 * @return a bound shader.
 */
@RequiresApi(AGSL_MIN_API)
internal fun buildGlassShader(uniforms: GlassUniforms): RuntimeShader {
    val shader = RuntimeShader(LUMEN_GLASS_AGSL)

    // Float uniforms. Kept in the same order as LUMEN_GLASS_UNIFORMS for easy auditing.
    shader.setFloatUniform("size", uniforms.widthPx, uniforms.heightPx)
    shader.setFloatUniform("cornerRadius", uniforms.cornerRadiusPx)
    shader.setFloatUniform("centreWeight", uniforms.centreWeight)
    shader.setFloatUniform("rimWeight", uniforms.rimWeight)
    shader.setFloatUniform("squircleExp", uniforms.squircleExp)
    shader.setFloatUniform("refraction", uniforms.refraction)
    shader.setFloatUniform("dispersion", uniforms.dispersion)
    shader.setFloatUniform("saturation", uniforms.saturation)
    shader.setFloatUniform("contrast", uniforms.contrast)
    shader.setFloatUniform("edgeLight", uniforms.edgeLight)
    shader.setFloatUniform("tier", uniforms.tier)
    shader.setFloatUniform("bezelAlpha", uniforms.bezelAlpha)
    shader.setFloatUniform("bezelRadiusPx", uniforms.bezelRadiusPx)

    // `tint` is declared `layout(color) uniform half4`, so it must be bound as a colour — a
    // setFloatUniform here would throw. This is exactly the kind of per-uniform detail that is easy
    // to get wrong from a distance.
    shader.setColorUniform("tint", uniforms.tintArgb)

    return shader
}

/**
 * Builds a shader, or `null` when the device cannot run AGSL.
 *
 * A safe wrapper around [buildGlassShader] so callers never have to reason about the API gate
 * themselves; the `@RequiresApi` is confined to this one function.
 *
 * @return a bound shader, or `null` on API < 33.
 */
internal fun buildGlassShaderOrNull(uniforms: GlassUniforms): RuntimeShader? =
    if (isAgsSupported()) buildGlassShader(uniforms) else null
