package com.subhankar.lumenglass.android

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

/**
 * The **effect** half of the Lumen Glass substrate.
 *
 * [GlassCapture] records content into a `RenderNode`; this turns the library's clean-room shader
 * into a `RenderEffect` that can be applied to that node. Together they are the "own the substrate"
 * decision from **Q2** — the library depends on nothing from `backdrop` or any other glass library
 * in order to render.
 *
 * ## Why a separate file from `GlassRenderer`
 *
 * `GlassRenderer` answers *"what shader, with what uniforms?"* — it knows about [GlassUniforms] and
 * uniform binding. This file answers *"how does that shader become a draw operation?"* — the
 * `RenderEffect` route, and the direct `Shader` route.
 *
 * Keeping them apart matters because the two have different failure modes: a uniform mistake throws
 * loudly at bind time inside `GlassRenderer`, whereas a mis-applied effect silently produces a
 * surface that draws *without* the material — a much harder bug to notice.
 *
 * ## API status: PROVISIONAL (pre-P4)
 *
 * Public so the harness can drive it. Not the designed API — that is P4.
 */
@RequiresApi(AGSL_MIN_API)
internal fun buildGlassRenderEffect(uniforms: GlassUniforms): RenderEffect =
    RenderEffect.createRuntimeShaderEffect(
        buildGlassShader(uniforms),
        CONTENT_SAMPLER_NAME,
    )

/**
 * Builds the glass effect with an optional **frost** (blur) pass in front of the lens.
 *
 * ## Why the platform blur, not a hand-written one
 *
 * `PLAN.md` §5.2 originally planned a hand-written two-pass AGSL blur ("81 → 18 taps"). The API
 * makes that unnecessary: `RenderEffect.createBlurEffect(radiusX, radiusY, input, tileMode)` takes
 * **independent X and Y radii**, so the framework's blur is *already* separable and
 * hardware-accelerated. A shader loop of our own would cost more, add an untested path, and
 * re-implement something the platform does better.
 *
 * Blur is therefore chained **in front of** the lens: content is blurred first, then refracted.
 * That ordering matters — blurring the *refracted* result would smear the lens's edge definition.
 *
 * @param frostRadiusPx blur radius in px; `<= 0` skips the blur entirely so the cheaper tiers pay
 *   nothing for it. Applied isotropically (the spec exposes one radius); the API supports
 *   anisotropic blur, but `GlassSpec` does not surface it yet.
 * @return the effect chain: `blur -> lens`, or just `lens` when frost is off.
 */
@RequiresApi(AGSL_MIN_API)
internal fun buildGlassRenderEffect(
    uniforms: GlassUniforms,
    frostRadiusPx: Float,
): RenderEffect {
    val lens = buildGlassRenderEffect(uniforms)
    if (frostRadiusPx <= 0f) return lens

    // TileMode.CLAMP, not DECAL: DECAL would bleed transparent edges inward and darken the rim,
    // fighting the shader's own edge-light term.
    return RenderEffect.createBlurEffect(
        frostRadiusPx,
        frostRadiusPx,
        lens,
        Shader.TileMode.CLAMP,
    )
}

/**
 * Builds the glass effect, or `null` below API 33 where AGSL does not exist.
 *
 * Callers **must** handle `null` by drawing the FROST fallback rather than skipping the surface —
 * the graceful-degradation contract from `QualityTier.FALLBACK`. Returning `null` (rather than
 * throwing) makes that contract impossible to ignore by accident.
 *
 * @return the effect, or `null` on API < 33.
 */
internal fun buildGlassRenderEffectOrNull(uniforms: GlassUniforms, frostRadiusPx: Float = 0f): RenderEffect? =
    if (isAgsSupported()) buildGlassRenderEffect(uniforms, frostRadiusPx) else null

/**
 * The name of the shader input the effect binds.
 *
 * `RenderEffect.createRuntimeShaderEffect` requires the **name of the shader uniform** its input
 * arrives on. Our shader declares `uniform shader content;` (asserted by
 * `LumenGlassShaderStructureTest`), so the name is `content`. Declared once, here.
 */
internal const val CONTENT_SAMPLER_NAME: String = "content"

/**
 * Applies the glass material to a [GlassCapture]'s node.
 *
 * This is the normal P2 path: capture content, then render it through the lens.
 *
 * Returns `false` when AGSL is unavailable (API < 33), so the caller can take the FROST branch. A
 * boolean rather than an exception because "this device cannot run the shader" is an expected,
 * documented state — not an error.
 *
 * @return `true` if the effect was applied, `false` if it could not be.
 */
@RequiresApi(RENDER_EFFECT_MIN_API)
internal fun applyGlassEffect(
    capture: GlassCapture,
    uniforms: GlassUniforms,
    frostRadiusPx: Float = 0f,
): Boolean {
    if (capture.isReleased) return false
    val effect = buildGlassRenderEffectOrNull(uniforms, frostRadiusPx) ?: return false
    capture.node.setRenderEffect(effect)
    return true
}

/**
 * Renders the glass material **directly** onto [canvas], with no intermediate capture node.
 *
 * The capture+effect route ([applyGlassEffect]) is the right one when there is content behind the
 * surface to refract. This route is for the case where there is not: a surface that generates its
 * own content, or a harness that wants to isolate the shader from layer-capture timing.
 *
 * `RuntimeShader` is a `Shader`, so it is assigned straight to the `Paint` — the documented route,
 * and the same mechanism the Compose harness uses.
 *
 * @return `true` if drawn, `false` for a degenerate size (caller should draw the FROST fallback).
 */
@RequiresApi(AGSL_MIN_API)
internal fun drawGlassWithShader(
    canvas: Canvas,
    shader: RuntimeShader,
    widthPx: Float,
    heightPx: Float,
    paint: Paint,
): Boolean {
    if ((widthPx <= 0f) || (heightPx <= 0f)) return false
    paint.shader = shader
    canvas.drawRect(0f, 0f, widthPx, heightPx, paint)
    return true
}
