/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import android.graphics.Canvas
import android.graphics.RenderNode
import androidx.annotation.RequiresApi
import com.subhankar.lumenglass.android.RENDER_NODE_MIN_API

/**
 * The **one** effect-free layer a [LumenGlassSourceLayout] records its content into.
 *
 * ## Why a RenderNode rather than a Bitmap
 *
 * The glass samples the source through a `RenderEffect`, which needs a GPU-resident node. Recording
 * into a [RenderNode] keeps the content on the GPU end to end: the glass re-records a cheap
 * `drawRenderNode` reference each time it redraws, instead of re-rasterising the source subtree or
 * copying a bitmap.
 *
 * The node is deliberately **effect-free**: the lens belongs to each glass surface, not to the
 * source. A source with an effect would bake one surface's material into the shared backdrop.
 *
 * ## Lifetime
 *
 * One node per source layout, created lazily on the first hardware draw and discarded when the
 * layout detaches. A [RenderNode] has no explicit `release()`; [release] discards its display list
 * and the reference is then dropped, which is what frees the native object.
 */
@RequiresApi(RENDER_NODE_MIN_API)
internal class GlassSource {

    /** The recorded subtree. Drawn by every glass surface that samples this source. */
    val node: RenderNode = RenderNode("lumen-glass-source")

    /**
     * `true` once a non-degenerate [record] has completed.
     *
     * Glass surfaces check this before sampling: an empty node would be transparent, and a
     * transparent glass surface reads as broken rather than as a deliberate fallback.
     */
    var hasContent: Boolean = false
        private set

    private var isReleased = false

    /**
     * Records [draw] into [node] at `widthPx × heightPx`, in the source's own coordinates.
     *
     * The origin is always the source layout's top-left; the glass supplies the on-screen offset
     * when it draws the node, so moving either view does not require re-recording the source.
     *
     * A zero or negative size is a no-op that clears [hasContent]: `RenderNode.beginRecording`
     * rejects a zero dimension, and a layout can legitimately be measured at zero during a
     * transition.
     *
     * @param widthPx recorded width, in px.
     * @param heightPx recorded height, in px.
     * @param draw records the source content. Runs on the UI thread.
     */
    fun record(widthPx: Int, heightPx: Int, draw: (Canvas) -> Unit) {
        if (isReleased) return
        if (widthPx <= 0 || heightPx <= 0) {
            hasContent = false
            return
        }

        val canvas = node.beginRecording(widthPx, heightPx)
        try {
            draw(canvas)
        } finally {
            // Always end the recording, even if `draw` threw: leaving a node mid-recording corrupts
            // every later draw. Same contract as GlassCapture.record.
            node.endRecording()
        }
        node.setPosition(0, 0, widthPx, heightPx)
        hasContent = true
    }

    /** Discards the recorded content. Idempotent. */
    fun release() {
        if (isReleased) return
        isReleased = true
        hasContent = false
        node.discardDisplayList()
    }
}
