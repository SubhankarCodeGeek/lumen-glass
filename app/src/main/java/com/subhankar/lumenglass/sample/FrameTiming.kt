/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.sample

import android.view.Choreographer

/**
 * Frame-time sampling for the P3.5 performance HUD.
 *
 * Lives in the **harness**, not the library: measuring frame time is an app concern, and the library
 * has no business owning a `Choreographer` callback.
 *
 * ## Why Choreographer and not a timer
 *
 * The exit criterion in `PLAN.md` §6.1 is about *frame delivery* (≥60fps), not about how long our
 * `onDraw` takes. Sampling the vsync callbacks measures what the user actually sees, including the
 * GPU work our `RenderEffect` adds — which a CPU-side stopwatch around the draw call would miss
 * entirely (the effect is asynchronous).
 *
 * ## What it reports
 *
 * Frame *intervals* (deltas between successive vsyncs), not frame durations. At 60Hz a healthy
 * interval is ~16.7ms; a janky frame shows up as a long interval followed by a short one. The HUD
 * shows a rolling average plus the worst interval seen, because an average alone hides exactly the
 * stalls the criterion cares about.
 */
class FrameTiming {

    /** Rolling window size. ~2s at 60Hz — long enough to be stable, short enough to react. */
    private val windowSize = 120

    private val intervals = ArrayDeque<Long>(windowSize)

    private var lastFrameNanos = 0L
    private var worstIntervalMs = 0f
    private var running = false

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) {
                val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
                // Discard absurd deltas (e.g. the app was paused) so they do not poison the stats.
                if (deltaMs < MAX_PLAUSIBLE_INTERVAL_MS) {
                    intervals.addLast(deltaMs.toLong())
                    while (intervals.size > windowSize) intervals.removeFirst()
                    if (deltaMs > worstIntervalMs) worstIntervalMs = deltaMs
                }
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Begins sampling. Safe to call repeatedly. */
    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        worstIntervalMs = 0f
        intervals.clear()
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** Stops sampling. Safe to call repeatedly. */
    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    /** Clears the rolling stats without stopping. Used when switching tiers. */
    fun reset() {
        intervals.clear()
        worstIntervalMs = 0f
        lastFrameNanos = 0L
    }

    /** Average frame interval over the window, in ms. `0f` until enough frames arrive. */
    fun averageIntervalMs(): Float =
        if (intervals.isEmpty()) 0f else intervals.sum().toFloat() / intervals.size

    /** The longest frame interval seen since the last [reset], in ms. */
    fun worstIntervalMs(): Float = worstIntervalMs

    /**
     * Approximate frames per second, derived from the average interval.
     *
     * `0f` when no frames have been sampled, so the HUD can distinguish "not measured yet" from
     * "measured and terrible".
     */
    fun fps(): Float {
        val avg = averageIntervalMs()
        return if (avg <= 0f) 0f else 1000f / avg
    }

    /** A compact one-line summary for the HUD. */
    fun summary(): String {
        val avg = averageIntervalMs()
        if (avg <= 0f) return "frames: measuring…"
        return "fps ~${"%.1f".format(fps())}  avg ${"%.1f".format(avg)}ms  worst " +
            "${"%.1f".format(worstIntervalMs())}ms  n=${intervals.size}"
    }

    private companion object {
        /** Deliberately generous: this only needs to exclude paused/resumed gaps. */
        const val MAX_PLAUSIBLE_INTERVAL_MS = 1000f
    }
}
