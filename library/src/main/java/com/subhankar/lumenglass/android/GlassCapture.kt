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
 * The capture records the caller's content and holds the recorded region so the overlap guard can
 * be checked against it. Reduced-resolution capture is implemented (`DownscaleCapture`): when
 * [record] is called with a scale below 1, the content is recorded into an internal buffer-sized
 * node and then composited back up into the public presentation node. Recording and lens evaluation
 * therefore happen at buffer resolution; the presentation upscale is a GPU composite.
 *
 * **Not implemented yet:**
 * - render-once / caching when the content behind is unchanged,
 * - separable two-pass blur (the platform blur is already separable — see `GlassEffect.kt`).
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
     * The **full-region presentation node** callers draw.
     *
     * At `scale == 1f` this node holds the recorded content directly. Below 1f it holds a
     * GPU-composited upscale of the internal content node, so it still occupies exactly the
     * surface-space region passed to [record] and remains directly drawable at full size.
     *
     * Exposed so the effect path can attach a `RenderEffect` and the harness can draw it. Not part
     * of any public API — P1.7 has no public API (that is P4).
     */
    val node: RenderNode = RenderNode("lumen-glass-capture")

    /**
     * The buffer-resolution content node used when [record] runs below full scale.
     *
     * Created lazily and only on the reduced path; `null` means the current recording is
     * full-resolution and the effect belongs on [node] directly.
     */
    private var contentNode: RenderNode? = null

    /**
     * The region currently recorded into the capture, in **surface coordinates** (never scaled).
     *
     * For a reduced-scale capture this is the full presentation region, not the buffer bounds: the
     * overlap guard must compare against where the glass sits on screen.
     */
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
     *
     * ## Reduced resolution uses two nodes
     *
     * At `scale == 1f` the content is recorded directly into [node] and the effect is attached
     * there — the original single-node path, unchanged. Below 1f the caller's draw is recorded into
     * an internal buffer-sized node (with the canvas scaled down), and [node] records the full
     * region by drawing that node back at `region / buffer` scale. [node] therefore remains the
     * full-region presentation node: callers keep drawing it at the region they passed in and never
     * see the reduced buffer.
     *
     * ## Uniforms stay in surface space
     *
     * [GlassUniforms] passed to [applyGlass] must always be in **surface coordinates**, even on the
     * reduced path. `applyGlass` converts `widthPx`/`heightPx`/`cornerRadiusPx`/`bezelRadiusPx`
     * (and any frost radius) into buffer space when it attaches the effect to the internal content
     * node.
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

        if (geometry.scale >= 1f) {
            // Full resolution: one node, exactly the pre-P3.5 behaviour. A content node left over
            // from an earlier reduced-scale record must go — `effectNode()` treats its existence as
            // "the current recording is buffer-space", so it must not outlive the reduced path.
            contentNode?.discardDisplayList()
            contentNode = null

            // Size the BUFFER by the scale, but record the REGION unscaled (see the note above).
            val canvas = node.beginRecording(geometry.bufferWidthPx, geometry.bufferHeightPx)
            try {
                // The caller's draw code works in surface coordinates, so scale the canvas down to
                // fit the smaller buffer. This keeps the content geometrically identical, just
                // coarser.
                canvas.scale(geometry.scale, geometry.scale)
                draw(canvas)
            } finally {
                // Must always end the recording, even if `draw` threw — otherwise the node stays in
                // a recording state and subsequent draws are corrupted. This is a real failure mode
                // with RenderNode, not defensive paranoia.
                node.endRecording()
            }
        } else {
            // Reduced resolution: record into the small content node, then composite it up into the
            // full-region presentation node. The upscale is a GPU draw, so the composite is cheap
            // and the caller still draws `node` at the original surface region.
            val content = contentNode ?: RenderNode("lumen-glass-capture-content").also {
                contentNode = it
            }
            val contentCanvas =
                content.beginRecording(geometry.bufferWidthPx, geometry.bufferHeightPx)
            try {
                contentCanvas.scale(geometry.scale, geometry.scale)
                draw(contentCanvas)
            } finally {
                content.endRecording()
            }
            content.setPosition(0, 0, geometry.bufferWidthPx, geometry.bufferHeightPx)

            val regionWidthPx = (right.toInt() - left.toInt()).coerceAtLeast(1)
            val regionHeightPx = (bottom.toInt() - top.toInt()).coerceAtLeast(1)
            val presentationCanvas = node.beginRecording(regionWidthPx, regionHeightPx)
            try {
                presentationCanvas.scale(
                    regionWidthPx.toFloat() / geometry.bufferWidthPx,
                    regionHeightPx.toFloat() / geometry.bufferHeightPx,
                )
                presentationCanvas.drawRenderNode(content)
            } finally {
                node.endRecording()
            }
        }

        recorded = RectF(left, top, right, bottom)

        // Position the node so its content lands at the recorded region's origin.
        node.setPosition(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /** The geometry used by the last [record] call (scale + buffer size). */
    internal fun captureGeometry(): CaptureGeometry = geometry

    /**
     * The node the glass effect must be attached to.
     *
     * On the reduced path this is the buffer-resolution content node; at full scale it is the
     * public [node] itself. Exists so the effect is always attached to the node that actually holds
     * the recorded content, without the effect path needing to know which geometry was used.
     */
    internal fun effectNode(): RenderNode = contentNode ?: node

    /** The region recorded by the last [record] call, or an empty rect if none. */
    fun recordedRegion(): RectF {
        check(!isReleased) { "GlassCapture has been released" }
        return RectF(recorded)
    }

    /**
     * Applies the glass material to this capture's effect node.
     *
     * The P2 substrate in one call: record (above), then render through the lens (here).
     *
     * [uniforms] are always in **surface coordinates**; on a reduced-scale capture they are
     * converted to buffer space internally, so callers use the same values at every scale. See
     * [record].
     *
     * @return `true` if the effect was applied; `false` when AGSL is unavailable (API < 33) or the
     *   capture has been released, in which case the caller draws the FROST fallback.
     */
    @RequiresApi(RENDER_EFFECT_MIN_API)
    fun applyGlass(uniforms: GlassUniforms): Boolean = applyGlassEffect(this, uniforms)

    /** The recorded region as an integer [Rect], for `setPosition` / `RenderNode` interop. */
    fun recordedRegionInt(): Rect {
        check(!isReleased) { "GlassCapture has been released" }
        return Rect(
            recorded.left.toInt(),
            recorded.top.toInt(),
            recorded.right.toInt(),
            recorded.bottom.toInt(),
        )
    }

    /**
     * Checks whether [surface] overlaps the region recorded into this capture.
     *
     * Thin bridge from the Android [RectF] world to the pure [checkOverlap] decision, so the
     * decision logic itself stays JVM-testable.
     *
     * @param surface the glass surface rect, in the same coordinate space as the recorded region.
     */
    fun checkSurfaceOverlap(surface: RectF): OverlapVerdict {
        check(!isReleased) { "GlassCapture has been released" }
        return checkOverlap(surface = surface.toOverlapRect(), capture = recorded.toOverlapRect())
    }

    /**
     * Frees both underlying native nodes.
     *
     * Idempotent — calling twice is safe. After release the capture must not be used again. Note
     * that [RenderNode] has no explicit `release()`; dropping the reference is what frees it, so
     * this flips the flag, discards both display lists, and nulls the content node to make reuse
     * fail fast.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        node.discardDisplayList()
        contentNode?.discardDisplayList()
        contentNode = null
    }
}
