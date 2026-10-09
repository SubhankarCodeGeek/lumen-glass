package com.subhankar.lumenglass.android

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
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
fun buildGlassRenderEffect(uniforms: GlassUniforms): RenderEffect =
    RenderEffect.createRuntimeShaderEffect(
        buildGlassShader(uniforms),
        CONTENT_SAMPLER_NAME,
    )

/**
 * Builds the glass effect, or `null` below API 33 where AGSL does not exist.
 *
 * Callers **must** handle `null` by drawing the FROST fallback rather than skipping the surface —
 * the graceful-degradation contract from `QualityTier.FALLBACK`. Returning `null` (rather than
 * throwing) makes that contract impossible to ignore by accident.
 *
 * @return the effect, or `null` on API < 33.
 */
fun buildGlassRenderEffectOrNull(uniforms: GlassUniforms): RenderEffect? =
    if (isAgsSupported()) buildGlassRenderEffect(uniforms) else null

/**
 * The name of the shader input the effect binds.
 *
 * `RenderEffect.createRuntimeShaderEffect` requires the **name of the shader uniform** its input
 * arrives on. Our shader declares `uniform shader content;` (asserted by
 * `LumenGlassShaderStructureTest`), so the name is `content`. Declared once, here.
 */
const val CONTENT_SAMPLER_NAME: String = "content"

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
fun applyGlassEffect(capture: GlassCapture, uniforms: GlassUniforms): Boolean {
    if (capture.isReleased) return false
    val effect = buildGlassRenderEffectOrNull(uniforms) ?: return false
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
fun drawGlassWithShader(
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
