/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import android.graphics.Canvas
import android.graphics.Paint
import com.subhankar.lumenglass.android.FROST_PANEL_ARGB

/**
 * The FROST fallback surface: a translucent rounded panel, no shader.
 *
 * Drawn whenever the glass path cannot run — below API 33, on a software canvas, when no source is
 * bound, or before the source has recorded a frame. The appearance is shared with the Compose
 * backend through [FROST_PANEL_ARGB] so a fallback never depends on which API the app used.
 *
 * One instance per glass view, holding its [Paint]; views are UI-thread-only, so the paint needs no
 * synchronisation and is not shared across views.
 */
internal class GlassFrostPanel {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Draws the panel to fill `widthPx × heightPx`.
     *
     * A zero size is a no-op: the view can be laid out at zero during a transition, and drawing an
     * empty rounded rect is pointless work.
     *
     * @param cornerRadiusPx the surface's corner radius in px (`0` = sharp corners).
     */
    fun draw(canvas: Canvas, widthPx: Int, heightPx: Int, cornerRadiusPx: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        paint.color = FROST_PANEL_ARGB
        canvas.drawRoundRect(
            0f,
            0f,
            widthPx.toFloat(),
            heightPx.toFloat(),
            cornerRadiusPx,
            cornerRadiusPx,
            paint,
        )
    }
}
