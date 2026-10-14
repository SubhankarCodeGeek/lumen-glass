/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.android

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import androidx.annotation.RequiresApi

/**
 * The recorded backdrop the glass shader samples.
 *
 * This is the **substrate** decision from Q2: Lumen Glass owns its own capture rather than
 * depending on `backdrop`.
 *
 * ## Why `RenderNode`
 *
 * The capture primitive is [RenderNode], **not** `android.graphics.GraphicsLayer`: the latter is a
 * **hidden framework API** — it is *not* present in `android.jar`, so a library that compiles
 * against the public SDK cannot use it (verified against the API 36 platform jar). `RenderNode` is
 * the public equivalent and provides everything the substrate needs (`beginRecording` /
 * `endRecording`, `setPosition`, `setRenderEffect`).
 *
 * ## Scope
 *
 * For P1.7 the capture is deliberately minimal — it records content at full resolution and holds the
 * recorded region so the overlap guard can be checked against it.
 *
 * **Deliberately not in P1.7** (these are P3.5, per PLAN §5.2):
 * - reduced-resolution capture (`DownscaleCapture`),
 * - render-once / caching when the content behind is unchanged,
 * - separable two-pass blur.
 *
 * Keeping it minimal means the P1.7 result — "does the shader compile, render, and bind" — is not
 * confounded by optimisation work.
 *
 * ## Lifecycle
 *
 * A [RenderNode] is a native object. It is owned by exactly one [GlassCapture] and freed by
 * [release]. This is the one obvious place a capture can leak, which is why lifetime is centralised
 * here rather than spread across call sites.
 *
 * ## API status: PROVISIONAL
 *
 * Public because [LumenGlassTestApi] exposes it to the harness, which renders several tiers side by
 * side — something the consumer API deliberately does not express. Not part of the consumer-facing
 * API; that is the `compose/` package.
 */
@RequiresApi(RENDER_NODE_MIN_API)
class GlassCapture {

    /**
     * The node the source content is recorded into.
     *
     * Exposed so the effect path can attach a `RenderEffect` and the harness can draw it. Not part
     * of any public API — P1.7 has no public API (that is P4).
     */
    val node: RenderNode = RenderNode("lumen-glass-capture")

    /** The region currently recorded into [node], in the node's own coordinate space. */
    private var recorded = RectF()

    /** `true` once [release] has run; further use is a caller bug. */
    var isReleased: Boolean = false
        private set

    /** The geometry used by the last [record] call. Defaults to a 1×1 full-scale placeholder. */
    private var geometry: CaptureGeometry = CaptureGeometry(1f, 1, 1)

    /**
     * Records [draw] into the capture node at the given region, at an optional reduced scale.
     *
     * ## Two sizes, and why they must not be confused
     *
     * - The **recorded region** (`left`/`top`/`right`/`bottom`) is in **surface coordinates**. It is
     *   what [checkSurfaceOverlap] compares, and it is stored **unscaled**.
     * - The **buffer size** is `scale` × the region, so the GPU rasterises fewer fragments.
     *
     * Scaling the recorded region as well would silently corrupt the overlap guard, because the
     * guard would then be comparing a shrunken rect against a real one. The split is the whole point
     * of [geometryFor].
     *
     * @param left left edge of the recorded region, in surface coordinates.
     * @param top top edge of the recorded region, in surface coordinates.
     * @param right right edge of the recorded region, in surface coordinates.
     * @param bottom bottom edge of the recorded region, in surface coordinates.
     * @param scale linear capture scale from `GlassFeatures.captureScale`; `1f` = full resolution.
     *   Clamped to `[MIN_CAPTURE_SCALE, 1]`, and the buffer is always at least 1×1 because
     *   `beginRecording` rejects a zero dimension.
     * @param draw the content to record. Runs against the node's recording canvas.
     */
    fun record(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        scale: Float = 1f,
        draw: (android.graphics.Canvas) -> Unit,
    ) {
        check(!isReleased) { "GlassCapture has been released" }

        val widthPx = (right - left).coerceAtLeast(1f)
        val heightPx = (bottom - top).coerceAtLeast(1f)
        val geometry = geometryFor(widthPx, heightPx, scale)
        this.geometry = geometry

        // Size the BUFFER by the scale, but record the REGION unscaled (see the note above).
        val canvas = node.beginRecording(geometry.bufferWidthPx, geometry.bufferHeightPx)
        try {
            // The caller's draw code works in surface coordinates, so scale the canvas down to fit
            // the smaller buffer. This keeps the content geometrically identical, just coarser.
            canvas.scale(geometry.scale, geometry.scale)
            draw(canvas)
        } finally {
            // Must always end the recording, even if `draw` threw — otherwise the node stays in a
            // recording state and subsequent draws are corrupted. This is a real failure mode with
            // RenderNode, not defensive paranoia.
            node.endRecording()
        }

        recorded = RectF(left, top, right, bottom)

        // Position the node so its content lands at the recorded region's origin.
        node.setPosition(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /** The geometry used by the last [record] call (scale + buffer size). */
    internal fun captureGeometry(): CaptureGeometry = geometry

    /** The region recorded by the last [record] call, or an empty rect if none. */
    fun recordedRegion(): RectF = RectF(recorded)

    /**
     * Applies the glass material to this capture's node.
     *
     * The P2 substrate in one call: record (above), then render through the lens (here).
     *
     * @return `true` if the effect was applied; `false` when AGSL is unavailable (API < 33) or the
     *   capture has been released, in which case the caller draws the FROST fallback.
     */
    @RequiresApi(RENDER_EFFECT_MIN_API)
    fun applyGlass(uniforms: GlassUniforms): Boolean = applyGlassEffect(this, uniforms)

    /** The recorded region as an integer [Rect], for `setPosition` / `RenderNode` interop. */
    fun recordedRegionInt(): Rect = Rect(
        recorded.left.toInt(),
        recorded.top.toInt(),
        recorded.right.toInt(),
        recorded.bottom.toInt(),
    )

    /**
     * Checks whether [surface] overlaps the region recorded into this capture.
     *
     * Thin bridge from the Android [RectF] world to the pure [checkOverlap] decision, so the
     * decision logic itself stays JVM-testable.
     *
     * @param surface the glass surface rect, in the same coordinate space as the recorded region.
     */
    fun checkSurfaceOverlap(surface: RectF): OverlapVerdict =
        checkOverlap(surface = surface.toOverlapRect(), capture = recorded.toOverlapRect())

    /**
     * Frees the underlying native node.
     *
     * Idempotent — calling twice is safe. After release the capture must not be used again. Note
     * that [RenderNode] has no explicit `release()`; dropping the reference is what frees it, so
     * this flips the flag and nulls the content to make reuse fail fast.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        node.discardDisplayList()
    }
}
