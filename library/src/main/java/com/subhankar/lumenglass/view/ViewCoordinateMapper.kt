/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import android.view.View

/**
 * The offset from a glass surface's origin to its source's origin, in px.
 *
 * Positive values mean the source starts to the **right of / below** the glass. To sample the
 * source, the glass translates its recording canvas by `(-x, -y)`.
 */
internal data class OffsetPx(val x: Float, val y: Float)

/**
 * Computes the offset between [glass]'s top-left corner and [source]'s top-left corner.
 *
 * ## Why window coordinates
 *
 * The glass records its source into a node at the source's own origin. When the glass draws that
 * node it must place it exactly where the source really is on screen, so the offset has to include
 * every intervening ancestor's position, scroll and (when attached) transform. `getLocationInWindow`
 * is the platform's answer for attached views and accounts for all of that.
 *
 * ## The detached fallback
 *
 * Instrumented tests and previews build hierarchies without a window, where
 * `getLocationInWindow` returns `(0, 0)` for every view and would silently collapse the offset to
 * zero. In that case the offset is summed from layout bounds and scroll positions up to the shared
 * root. Translations and scales are **not** included in the fallback — it is a test/preview
 * convenience, not a rendering contract.
 *
 * @return the offset, or `null` when the two views do not share a root view (different windows or
 *   disconnected subtrees), in which case there is no meaningful on-screen offset.
 */
internal fun offsetBetween(glass: View, source: View): OffsetPx? {
    if (glass.rootView !== source.rootView) return null

    if (glass.isAttachedToWindow && source.isAttachedToWindow) {
        val glassLocation = IntArray(2)
        val sourceLocation = IntArray(2)
        glass.getLocationInWindow(glassLocation)
        source.getLocationInWindow(sourceLocation)
        return OffsetPx(
            x = (sourceLocation[0] - glassLocation[0]).toFloat(),
            y = (sourceLocation[1] - glassLocation[1]).toFloat(),
        )
    }

    val glassLocation = locationInRoot(glass)
    val sourceLocation = locationInRoot(source)
    return OffsetPx(
        x = (sourceLocation[0] - glassLocation[0]).toFloat(),
        y = (sourceLocation[1] - glassLocation[1]).toFloat(),
    )
}

/**
 * The view's position relative to the top of its view tree, from layout bounds and scroll only.
 *
 * Both views in a comparison must share a root (checked by [offsetBetween]), so the two results are
 * in the same space and their difference is meaningful.
 */
private fun locationInRoot(view: View): IntArray {
    var x = 0
    var y = 0
    var current: View = view
    while (true) {
        x += current.left - current.scrollX
        y += current.top - current.scrollY
        val parent = current.parent
        if (parent !is View) break
        current = parent
    }
    return intArrayOf(x, y)
}
