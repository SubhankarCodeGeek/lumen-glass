/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.sample

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.subhankar.lumenglass.android.LumenGlassDebug

/**
 * Phase 8 harness — the **View/XML** surface.
 *
 * ## What this screen exercises
 *
 * `R.layout.activity_view_glass` is the whole feature, inflated and left running:
 *
 * - one [com.subhankar.lumenglass.view.LumenGlassSourceLayout] holding a `ScrollView` with 48
 *   saturated rows — the *only* thing a glass may sample, and a moving one, so scrolling proves the
 *   capture re-records,
 * - two [com.subhankar.lumenglass.view.LumenGlassView] siblings declared *after* the source, so they
 *   sample the frame the source has just recorded rather than the previous one,
 * - a `TextView` child inside the primary glass, drawn above the glass rather than sampled by it.
 *
 * The rows are populated here rather than in XML because 48 near-identical items in a layout file
 * bury the part that matters — the source/glass structure.
 *
 * ## Honest scope note
 *
 * This is a harness, not a benchmark. It shows the surfaces render and stay current while
 * scrolling; it makes no frame-rate claim.
 */
class ViewGlassActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LumenGlassDebug.enabled = true

        setContentView(R.layout.activity_view_glass)

        val rows = findViewById<LinearLayout>(R.id.view_glass_rows)
        populateRows(rows)
    }

    /**
     * Fills [container] with high-contrast, saturated rows.
     *
     * Alternating hues rather than a gradient: a gradient makes a lens look correct even when it is
     * displacing nothing, whereas hard colour edges and text expose displacement, blur and fringe.
     */
    private fun populateRows(container: LinearLayout) {
        val palette = intArrayOf(
            0xFF00E5FF.toInt(),
            0xFFFF6D00.toInt(),
            0xFF651FFF.toInt(),
            0xFF00E676.toInt(),
        )

        val height = (56 * resources.displayMetrics.density).toInt()
        val padding = (16 * resources.displayMetrics.density).toInt()

        for (index in 0 until ROW_COUNT) {
            val row = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    height,
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padding, 0, padding, 0)
                text = getString(R.string.view_glass_row, index + 1)
                setTextColor(if (index % 2 == 0) Color.BLACK else Color.WHITE)
                textSize = 16f
                setBackgroundColor(palette[index % palette.size])
            }
            container.addView(row)
        }
    }

    private companion object {
        const val ROW_COUNT = 48
    }
}
