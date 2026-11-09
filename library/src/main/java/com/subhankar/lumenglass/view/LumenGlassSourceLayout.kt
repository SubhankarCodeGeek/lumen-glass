/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.subhankar.lumenglass.android.RENDER_NODE_MIN_API
import java.lang.ref.WeakReference

/**
 * The **only** view type a [LumenGlassView] may sample as its source.
 *
 * ## Why a dedicated container
 *
 * An arbitrary sibling view is not an acceptable source. Recording one with `View.draw()` into a
 * capture canvas loses the parent transform, alpha and clip that positioned it on screen, and it is
 * double-rasterised in the process. Requiring a dedicated container makes the supported source
 * explicit and lets the binding layer reject everything else with a diagnosable reason instead of
 * falling back to a silent near-black render (see `SourceBinding.kt`).
 *
 * ## What it records
 *
 * On a hardware canvas it records **once per invalidation** into a single effect-free
 * [GlassSource] node:
 *
 * 1. the background,
 * 2. `onDraw`,
 * 3. every non-glass direct child, in order,
 *
 * then draws that node, the glass children (outside the node) and the foreground. This mirrors
 * `View.draw()`'s sequence, which is deliberate: the captured content must look exactly like what
 * the source would have drawn normally.
 *
 * The node is **effect-free** — each glass surface owns its own effect, so one source can feed many
 * surfaces and each gets its own material.
 *
 * ## Why glass children are excluded
 *
 * A `LumenGlassView` inside its own source is rejected at bind time (the source is an ancestor, and
 * an ancestor reference recurses through the glass). Drawing one into the node would also make the
 * source's node depend on the glass that samples it — a `RenderNode` cycle. Direct glass children
 * are therefore drawn on the outer canvas, at their normal on-screen position, and never enter the
 * node.
 *
 * ## Invalidation
 *
 * After recording, every bound glass surface is posted an invalidation. That keeps a fixed glass
 * surface live over scrolling content even when the surface itself was not invalidated. Declaring
 * the glass **after** the source gives same-frame freshness; declaring it before costs one frame.
 *
 * This cannot loop: a glass may never sample an ancestor, so invalidating a surface cannot dirty
 * the source it samples.
 *
 * ## Degraded path
 *
 * On API < 29 or a software canvas, capture is impossible (a software canvas cannot
 * `drawRenderNode`), so this falls back to `super.draw(canvas)` and children draw normally. Glass
 * surfaces then show the FROST panel. The layout remains a perfectly ordinary `FrameLayout`.
 */
@SuppressLint("NewApi") // Every API 29 use is behind an explicit Build.VERSION check; see draw().
public class LumenGlassSourceLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Created lazily on the first hardware draw; discarded on detach. */
    private var source: GlassSource? = null

    /** Bound surfaces, weakly held so a detached glass cannot be retained by its source. */
    private val consumers = ArrayList<WeakReference<View>>()

    /**
     * The node glass surfaces draw, or `null` before the first hardware draw.
     *
     * Internal on purpose: consumers draw the node, they never modify it.
     */
    internal fun glassSourceNode(): GlassSource? = source

    internal fun addConsumer(consumer: View) {
        if (consumers.any { it.get() === consumer }) return
        consumers.removeAll { it.get() == null }
        consumers.add(WeakReference(consumer))
    }

    internal fun removeConsumer(consumer: View) {
        consumers.removeAll { it.get() == null || it.get() === consumer }
    }

    override fun draw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT < RENDER_NODE_MIN_API || !canvas.isHardwareAccelerated) {
            // No RenderNode (API 29) or a software canvas (which cannot replay one): behave as a
            // normal FrameLayout. Glass surfaces fall back to the FROST panel.
            super.draw(canvas)
            return
        }
        if (width <= 0 || height <= 0) return

        val layer = source ?: GlassSource().also { source = it }
        layer.record(width, height) { recording ->
            val save = recording.save()
            try {
                drawSampledContent(recording)
            } finally {
                recording.restoreToCount(save)
            }
        }

        canvas.drawRenderNode(layer.node)

        // Glass children are excluded from the node (see the class KDoc), so they must be drawn
        // here, above the sampled content.
        drawGlassChildren(canvas)

        // Dividers/foreground belong above the content but must not be baked into the backdrop.
        onDrawForeground(canvas)

        notifyConsumers()
    }

    /** Records exactly the content a normal `View.draw()` would put under the children. */
    private fun drawSampledContent(canvas: Canvas) {
        // `View.drawBackground` is not in the public SDK surface, so reproduce its two observable
        // steps: size the background to the view, then draw it.
        val background = background
        if (background != null) {
            background.setBounds(0, 0, width, height)
            background.draw(canvas)
        }
        super.onDraw(canvas)

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is LumenGlassView) continue
            drawChild(canvas, child, drawingTime)
        }
    }

    /** Draws direct glass children on the outer canvas, at their normal positions. */
    private fun drawGlassChildren(canvas: Canvas) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !is LumenGlassView) continue
            val save = canvas.save()
            try {
                drawChild(canvas, child, drawingTime)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    /**
     * Posts a redraw to every bound glass surface after the node has been re-recorded.
     *
     * Prunes collected consumers as it walks, so a detached glass does not accumulate here.
     */
    private fun notifyConsumers() {
        if (consumers.isEmpty()) return
        val iterator = consumers.iterator()
        while (iterator.hasNext()) {
            val consumer = iterator.next().get()
            if (consumer == null) {
                iterator.remove()
                continue
            }
            if (consumer.isAttachedToWindow) consumer.postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The node is native memory; drop it when the layout leaves the window. The next hardware
        // draw re-creates it.
        source?.release()
        source = null
    }
}
