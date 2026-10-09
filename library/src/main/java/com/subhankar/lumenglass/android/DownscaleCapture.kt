package com.subhankar.lumenglass.android

import kotlin.math.roundToInt

/**
 * The reduced-resolution capture geometry.
 *
 * ## Why this exists as its own type
 *
 * `PLAN.md` §5.2 makes reduced-resolution capture the primary performance lever: glass is blurry by
 * definition, so rendering its backdrop at 0.25×–0.5× linear scale is invisible while cutting
 * fragment work by ~4×. That arithmetic is small but easy to get subtly wrong (rounding a layer to
 * zero, or accidentally scaling the *reported* region instead of only the pixel buffer), so it lives
 * in one tested place rather than inline in the capture path.
 *
 * ## The coordinate rule (the thing that must not break)
 *
 * Two different notions of "size" are in play, and conflating them would silently break the overlap
 * guard:
 *
 * - **Surface coordinates** — where the glass surface sits on screen. The overlap guard compares
 *   these. **They must not be scaled.**
 * - **Buffer coordinates** — the pixel dimensions of the capture buffer. These *are* scaled.
 *
 * So a `GlassCapture` records a region reported in surface coordinates while allocating a smaller
 * buffer. [geometryFor] produces the scaled buffer size; the caller keeps the unscaled region.
 *
 * @property scale the linear scale applied to the buffer, always in `(0, 1]`.
 * @property bufferWidthPx the capture buffer width in px (at least 1).
 * @property bufferHeightPx the capture buffer height in px (at least 1).
 */
internal data class CaptureGeometry(
    val scale: Float,
    val bufferWidthPx: Int,
    val bufferHeightPx: Int,
) {
    /** The texel-to-surface ratio, so the shader can map surface coordinates onto the buffer. */
    val inverseScale: Float get() = if (scale > 0f) 1f / scale else 1f
}

/**
 * Computes the capture geometry for a surface region.
 *
 * A scale outside `(0, 1]` is clamped rather than rejected: a caller passing `0f` (perhaps from a
 * misconfigured tier) should get a 1×1 buffer, not a crash or an invisible surface. The result is
 * always at least 1×1 because `RenderNode.beginRecording` rejects a zero dimension.
 *
 * @param widthPx the unscaled region width, in surface px.
 * @param heightPx the unscaled region height, in surface px.
 * @param scale the linear capture scale from `GlassFeatures.captureScale`.
 */
internal fun geometryFor(widthPx: Float, heightPx: Float, scale: Float): CaptureGeometry {
    val s = scale.coerceIn(MIN_CAPTURE_SCALE, 1f)
    val w = (widthPx * s).roundToInt().coerceAtLeast(1)
    val h = (heightPx * s).roundToInt().coerceAtLeast(1)
    return CaptureGeometry(scale = s, bufferWidthPx = w, bufferHeightPx = h)
}

/**
 * The smallest permitted capture scale.
 *
 * A floor rather than allowing arbitrarily tiny scales: below this the glass stops reading as
 * "frosted" and starts reading as "broken", which is the outcome `PLAN.md` §5.2 explicitly forbids
 * for the degraded tiers. 0.25 is the plan's stated lower bound.
 */
internal const val MIN_CAPTURE_SCALE: Float = 0.25f
