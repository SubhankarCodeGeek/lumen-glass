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
 * ## Why `RenderNode` and not `GraphicsLayer`
 *
 * The design docs (`ARCHITECTURE.md` §4/§5.1, `PLAN.md` §5.1) name `android.graphics.GraphicsLayer`
 * as the capture primitive. **That class is a hidden framework API** — it is *not* present in
 * `android.jar`, so a library that compiles against the public SDK cannot use it. Verified against
 * the API 36 platform jar. The public equivalent is [RenderNode], which provides everything the
 * substrate needs (`beginRecording` / `endRecording`, `setPosition`, `setRenderEffect`).
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
 * ## API status: PROVISIONAL (pre-P4)
 *
 * Public so the P1.7 harness module can drive it. Not the designed API — that is P4.
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

    /**
     * Records [draw] into the capture node at the given region.
     *
     * The node is sized to the region (in whole pixels, with a minimum of 1×1 — `beginRecording`
     * rejects a zero dimension). Content is then recorded at full resolution; the reduced-scale path
     * is P3.5.
     *
     * @param left left edge of the recorded region, in node coordinates.
     * @param top top edge of the recorded region, in node coordinates.
     * @param right right edge of the recorded region, in node coordinates.
     * @param bottom bottom edge of the recorded region, in node coordinates.
     * @param draw the content to record. Runs against the node's recording canvas.
     */
    fun record(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        draw: (android.graphics.Canvas) -> Unit,
    ) {
        check(!isReleased) { "GlassCapture has been released" }

        val widthPx = (right - left).toInt().coerceAtLeast(1)
        val heightPx = (bottom - top).toInt().coerceAtLeast(1)

        val canvas = node.beginRecording(widthPx, heightPx)
        try {
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

    /** The region recorded by the last [record] call, or an empty rect if none. */
    fun recordedRegion(): RectF = RectF(recorded)

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
