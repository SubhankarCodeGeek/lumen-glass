/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.sample

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Deterministic high-contrast content for the glass harnesses.
 *
 * Shared by every harness screen rather than duplicated, so the *content* is provably identical
 * across them. That matters for comparison: if the lens-comparison screen and the tier-comparison
 * screen drew different backgrounds, neither could be used to judge the other.
 *
 * ## Why high contrast
 *
 * A lens on a flat gradient is invisible — which is exactly how a broken lens hides. Hard edges,
 * saturated primaries and a fine grid make displacement, blur and chromatic fringe all legible.
 * The grid in particular makes *small* displacements visible; a flat field would not.
 */
internal object HighContrastContent {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
    }

    /**
     * Draws the pattern to fill [w] × [h].
     *
     * Stateless apart from the reused [Paint] instances (so it is safe to call from `onDraw`
     * without allocating per frame).
     */
    fun draw(canvas: Canvas, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.BLACK)

        paint.color = 0xFF00E5FF.toInt()
        canvas.drawRect(0f, h * 0.15f, w * 0.5f, h * 0.35f, paint)

        paint.color = 0xFFFF6D00.toInt()
        canvas.drawRect(w * 0.5f, h * 0.35f, w, h * 0.55f, paint)

        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(0f, h * 0.7f, w, h * 0.75f, paint)

        canvas.drawText("▚▞ LUMEN GLASS", w * 0.06f, h * 0.62f, textPaint)

        // A grid makes small displacements legible — a flat field hides them.
        paint.color = 0x66FFFFFF
        var x = 0f
        while (x < w) {
            canvas.drawRect(x, 0f, x + 2f, h, paint)
            x += 48f
        }
        var y = 0f
        while (y < h) {
            canvas.drawRect(0f, y, w, y + 2f, paint)
            y += 48f
        }
    }
}
