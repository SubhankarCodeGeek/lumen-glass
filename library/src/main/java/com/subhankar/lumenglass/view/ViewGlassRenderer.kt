/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import android.graphics.Canvas
import android.graphics.RenderEffect
import androidx.annotation.RequiresApi
import com.subhankar.lumenglass.android.AGSL_MIN_API
import com.subhankar.lumenglass.android.GlassCapture
import com.subhankar.lumenglass.android.GlassEffectSpace
import com.subhankar.lumenglass.android.GlassRenderPlan
import com.subhankar.lumenglass.android.attachGlassEffect
import com.subhankar.lumenglass.android.buildGlassRenderEffectOrNull
import com.subhankar.lumenglass.android.effectSpace
import com.subhankar.lumenglass.core.GlassFeatures

/**
 * The View backend's rendering engine for one glass surface.
 *
 * ## What it does
 *
 * For each draw it:
 *
 * 1. records a surface-sized capture whose only content is the shared source node, translated so
 *    the real on-screen offset is preserved;
 * 2. attaches the material to that capture (a `RenderEffect`, with the optional platform blur);
 * 3. draws the capture at the surface's origin.
 *
 * The source node is **referenced**, not copied: the capture records one `drawRenderNode` command,
 * so N glass surfaces over one source still rasterise the source once. Every surface has its own
 * capture, because each needs a different offset and its own effect.
 *
 * ## Why the effect is cached
 *
 * Building a `RenderEffect` constructs a `RuntimeShader` and binds sixteen uniforms; doing that in
 * every `onDraw` would rebuild the shader every frame. The effect only depends on the geometry and
 * material — both captured by [GlassEffectSpace] — so it is rebuilt only when that value changes.
 * The *content* is re-recorded every draw; that is the cheap part.
 *
 * ## API status
 *
 * Internal and API 33+. Below 33 [LumenGlassView] draws [GlassFrostPanel] instead and never
 * constructs this class, so the `@RequiresApi` is honoured by construction rather than by a
 * defensive check inside every method.
 */
@RequiresApi(AGSL_MIN_API)
internal class ViewGlassRenderer {

    private val capture = GlassCapture()

    private var effect: RenderEffect? = null
    private var effectSpace: GlassEffectSpace? = null

    /**
     * Renders the glass surface.
     *
     * @param canvas the surface's `onDraw` canvas; hardware-accelerated (checked by the caller).
     * @param surfaceWidthPx the surface's width.
     * @param surfaceHeightPx the surface's height.
     * @param offset the source origin relative to the surface origin, in px.
     * @param plan the resolved uniforms and frost radius (surface space).
     * @param features the resolved tier features; [GlassFeatures.captureScale] is applied here.
     * @param source the recorded, effect-free source.
     * @return `true` if the glass was drawn; `false` when there is nothing valid to sample or the
     *   effect could not be built, in which case the caller draws the frost fallback.
     */
    fun draw(
        canvas: Canvas,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        offset: OffsetPx,
        plan: GlassRenderPlan,
        features: GlassFeatures,
        source: GlassSource,
    ): Boolean {
        if (surfaceWidthPx <= 0 || surfaceHeightPx <= 0) return false
        if (!source.hasContent) return false

        // The capture is surface-sized and the source is translated into surface space. Recording
        // the source node (rather than re-drawing its subtree) is what keeps multiple glass
        // surfaces over one source cheap.
        capture.record(
            left = 0f,
            top = 0f,
            right = surfaceWidthPx.toFloat(),
            bottom = surfaceHeightPx.toFloat(),
            scale = features.captureScale,
        ) { recording ->
            recording.translate(-offset.x, -offset.y)
            recording.drawRenderNode(source.node)
        }

        val space = capture.effectSpace(plan.uniforms, plan.frostRadiusPx)
        val resolvedEffect = effect.takeIf { space == effectSpace }
            ?: buildGlassRenderEffectOrNull(space.uniforms, space.frostRadiusPx)?.also {
                effect = it
                effectSpace = space
            }

        // `null` means AGSL is unavailable (the caller already checks the tier, so this is a
        // defensive contract, not a live path): the surface falls back to FROST rather than
        // drawing an un-materialised capture.
        if (resolvedEffect == null) return false

        capture.attachGlassEffect(resolvedEffect)
        canvas.drawRenderNode(capture.node)
        return true
    }

    /**
     * Frees the capture's native nodes and the cached effect.
     *
     * Idempotent from the caller's perspective: [LumenGlassView] nulls its renderer reference after
     * this, and a later draw constructs a fresh one.
     */
    fun release() {
        capture.release()
        effect = null
        effectSpace = null
    }
}
