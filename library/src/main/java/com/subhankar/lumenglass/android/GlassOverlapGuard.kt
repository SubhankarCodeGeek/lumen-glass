package com.subhankar.lumenglass.android

import android.graphics.RectF
import android.util.Log

/**
 * A plain, framework-free rectangle in surface coordinates.
 *
 * Deliberately **not** `android.graphics.RectF`, so the overlap decision can be unit-tested on the
 * JVM without Robolectric or a device. The Android entry points convert at the boundary.
 */
internal data class OverlapRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** `true` when this rect has no positive area — either inverted or degenerate. */
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun intersects(other: OverlapRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Area of the intersection with [other], or `0f` when they do not overlap. */
    fun intersectionArea(other: OverlapRect): Float {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }
}

/** Converts an Android [RectF] to the framework-free [OverlapRect]. */
internal fun RectF.toOverlapRect(): OverlapRect =
    OverlapRect(left = left, top = top, right = right, bottom = bottom)

/**
 * The verdict of an overlap check.
 *
 * Returned as a **value** rather than only logged, so a caller (e.g. a Compose harness) can surface
 * it on screen. The library never draws anything itself — see ARCHITECTURE §4.
 *
 * @property overlapping `true` when the glass surface overlaps the captured region.
 * @property intersectionFraction the intersect area as a fraction of the glass surface area,
 *   `0f`..`1f`. A tiny non-zero value means the surface only clips the capture — visually broken
 *   even though it technically "overlaps".
 * @property message a human-readable explanation, suitable for a log or a debug banner.
 */
internal data class OverlapVerdict(
    val overlapping: Boolean,
    val intersectionFraction: Float,
    val message: String,
)

/**
 * The **one** threshold at which a marginal overlap is treated as broken.
 *
 * A surface that technically intersects the capture by a sliver will still render as a near-black
 * smear across most of its face, so "any intersection at all" is too weak a test. Below this
 * fraction of the glass area we report failure.
 */
internal const val MIN_OVERLAP_FRACTION: Float = 0.01f

/**
 * Decides whether a glass surface overlaps the region recorded into the capture layer.
 *
 * **This is the pitfall from ARCHITECTURE §4.** If the surface does not overlap the captured region,
 * the shader samples empty space and renders **near-black** — the single hardest-to-diagnose failure
 * in this kind of substrate. Making it detectable is a first-class design requirement, not a
 * footnote.
 *
 * Pure function: no Android types, no logging. Unit-tested by `GlassOverlapGuardTest`.
 *
 * @param surface the glass surface rect, in the capture layer's coordinate space.
 * @param capture the recorded region, in the same space.
 * @return the [OverlapVerdict]; `overlapping = false` means the render will be broken.
 */
internal fun checkOverlap(surface: OverlapRect, capture: OverlapRect): OverlapVerdict {
    if (surface.isEmpty) {
        return OverlapVerdict(
            overlapping = false,
            intersectionFraction = 0f,
            message = "Glass surface rect has no area " +
                "(l=${surface.left}, t=${surface.top}, r=${surface.right}, b=${surface.bottom}).",
        )
    }
    if (capture.isEmpty) {
        return OverlapVerdict(
            overlapping = false,
            intersectionFraction = 0f,
            message = "Capture region has no area — nothing was recorded for the shader to sample.",
        )
    }

    val fraction = surface.intersectionArea(capture) / (surface.width * surface.height)
    if (fraction <= 0f) {
        return OverlapVerdict(
            overlapping = false,
            intersectionFraction = 0f,
            message = "Glass surface does NOT overlap the captured region — the shader will " +
                "sample empty space and render near-black. Surface=$surface, capture=$capture. " +
                "See ARCHITECTURE §4.",
        )
    }
    if (fraction < MIN_OVERLAP_FRACTION) {
        return OverlapVerdict(
            overlapping = false,
            intersectionFraction = fraction,
            message = "Glass surface only overlaps ${(fraction * 100).toInt()}% of the captured " +
                "region — below the ${(MIN_OVERLAP_FRACTION * 100).toInt()}% floor. Most of the " +
                "surface will render near-black.",
        )
    }

    return OverlapVerdict(
        overlapping = true,
        intersectionFraction = fraction,
        message = "OK — surface overlaps ${(fraction * 100).toInt()}% of the captured region.",
    )
}

/**
 * Debug-only logging of an [OverlapVerdict].
 *
 * Per the approved plan (Q-A), the guard **warns** rather than throwing: a library that crashes a
 * consumer's app on a layout mistake is worse than one that renders oddly and says why. The harness
 * additionally surfaces the verdict on screen.
 *
 * Logging is confined to debug builds so a shipped app does not spam logcat.
 */
internal fun logOverlapVerdict(verdict: OverlapVerdict, tag: String = TAG) {
    if (!LumenGlassDebug.enabled) return
    if (verdict.overlapping) {
        Log.d(tag, verdict.message)
    } else {
        Log.w(tag, verdict.message)
    }
}

/** The logcat tag used by the glass render path. */
internal const val TAG: String = "LumenGlass"

/**
 * Whether debug-only instrumentation (logging, on-screen banners) is active.
 *
 * The library cannot read the consumer's `BuildConfig`, so the harness/app opts in. Defaults to
 * `false` so a release consumer gets no log spam unless they ask for it.
 */
internal object LumenGlassDebug {
    /** Set to `true` by a debug harness to enable logging + banner surfacing. */
    @Volatile
    var enabled: Boolean = false
}
