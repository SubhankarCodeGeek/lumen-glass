/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.subhankar.lumenglass.R
import com.subhankar.lumenglass.android.LumenGlassDebug
import com.subhankar.lumenglass.android.TAG
import com.subhankar.lumenglass.core.QualityTier
import com.subhankar.lumenglass.core.RimProfile

/**
 * Reads the `LumenGlassView` styleable into [ParsedGlassAttributes].
 *
 * ## One read, at construction
 *
 * Attributes are parsed once in the `View` constructor and stored as a plain [GlassSpec]. Nothing in
 * the draw path touches a `TypedArray`, and the array is recycled immediately — leaking one is a
 * classic Android resource bug.
 *
 * ## Units
 *
 * `format="dimension"` attributes arrive in **px** and are divided by the display density here, so
 * the stored [GlassSpec] is in dp exactly like every other spec in the library. The renderer then
 * multiplies by density once. Doing the conversion anywhere else would double-scale.
 */
internal object LumenGlassAttrs {

    /** The `lumenGlassQuality` value that means "let the capability probe choose". */
    private const val QUALITY_AUTO: Int = 0

    /**
     * Reads [attrs] against the `LumenGlassView` styleable.
     *
     * @param context used for density and for `obtainStyledAttributes`.
     * @param attrs the constructor's attributes (never null when called, but nullable in the View).
     * @param defStyleAttr the theme attribute supplied to the View constructor.
     */
    fun read(
        context: Context,
        attrs: AttributeSet,
        defStyleAttr: Int,
    ): ParsedGlassAttributes {
        val a = context.obtainStyledAttributes(
            attrs,
            R.styleable.LumenGlassView,
            defStyleAttr,
            0,
        )
        try {
            val density = context.resources.displayMetrics.density.let { if (it > 0f) it else 1f }

            fun has(attr: Int): Boolean = a.hasValue(attr)
            fun floatAttr(attr: Int): Float? = if (has(attr)) a.getFloat(attr, 0f) else null
            fun intAttr(attr: Int): Int? = if (has(attr)) a.getInt(attr, 0) else null
            fun dimensionDp(attr: Int): Float? =
                if (has(attr)) a.getDimension(attr, 0f) / density else null

            val values = GlassAttrValues(
                lensModel = parseEnum(
                    raw = intAttr(R.styleable.LumenGlassView_lumenGlassLensModel),
                    name = "lumenGlassLensModel",
                    mapping = ::lensModelTag,
                ),
                centreWeight = floatAttr(R.styleable.LumenGlassView_lumenGlassCentreWeight),
                rimWeight = floatAttr(R.styleable.LumenGlassView_lumenGlassRimWeight),
                refraction = floatAttr(R.styleable.LumenGlassView_lumenGlassRefraction),
                dispersion = floatAttr(R.styleable.LumenGlassView_lumenGlassDispersion),
                frostDp = dimensionDp(R.styleable.LumenGlassView_lumenGlassFrost),
                tintArgb = intAttr(R.styleable.LumenGlassView_lumenGlassTint),
                saturation = floatAttr(R.styleable.LumenGlassView_lumenGlassSaturation),
                contrast = floatAttr(R.styleable.LumenGlassView_lumenGlassContrast),
                edgeLight = floatAttr(R.styleable.LumenGlassView_lumenGlassEdgeLight),
                cornerRadiusDp = dimensionDp(R.styleable.LumenGlassView_lumenGlassCornerRadius),
                precisionMode = if (has(R.styleable.LumenGlassView_lumenGlassPrecisionMode)) {
                    a.getBoolean(R.styleable.LumenGlassView_lumenGlassPrecisionMode, true)
                } else {
                    null
                },
                rimProfile = parseEnum(
                    raw = intAttr(R.styleable.LumenGlassView_lumenGlassRimProfile),
                    name = "lumenGlassRimProfile",
                    mapping = ::rimProfile,
                ),
                bezelRadiusDp = dimensionDp(R.styleable.LumenGlassView_lumenGlassBezelRadius),
                bezelAlpha = floatAttr(R.styleable.LumenGlassView_lumenGlassBezelAlpha),
            )

            val enabled = if (has(R.styleable.LumenGlassView_lumenGlassEnabled)) {
                a.getBoolean(R.styleable.LumenGlassView_lumenGlassEnabled, true)
            } else {
                true
            }

            val sourceId = if (has(R.styleable.LumenGlassView_lumenGlassSource)) {
                a.getResourceId(R.styleable.LumenGlassView_lumenGlassSource, View.NO_ID)
                    .takeIf { it != View.NO_ID }
            } else {
                null
            }

            return ParsedGlassAttributes(
                values = values,
                enabled = enabled,
                quality = parseQuality(intAttr(R.styleable.LumenGlassView_lumenGlassQuality)),
                sourceId = sourceId,
            )
        } finally {
            a.recycle()
        }
    }

    private fun parseQuality(raw: Int?): QualityTier? {
        if (raw == null || raw == QUALITY_AUTO) return null
        val tier = when (raw) {
            1 -> QualityTier.HIGH
            2 -> QualityTier.BALANCED
            3 -> QualityTier.LOW
            4 -> QualityTier.FALLBACK
            else -> null
        }
        if (tier == null && LumenGlassDebug.enabled) {
            Log.w(TAG, "LumenGlassView: lumenGlassQuality=$raw is not a recognised value; " +
                "using capability selection.")
        }
        return tier
    }

    private fun <T> parseEnum(raw: Int?, name: String, mapping: (Int) -> T?): T? {
        if (raw == null) return null
        val mapped = mapping(raw)
        if (mapped == null && LumenGlassDebug.enabled) {
            Log.w(TAG, "LumenGlassView: $name=$raw is not a recognised value; using the default.")
        }
        return mapped
    }

    private fun lensModelTag(raw: Int): LensModelTag? = when (raw) {
        0 -> LensModelTag.CENTRE
        1 -> LensModelTag.RIM
        2 -> LensModelTag.BLEND
        else -> null
    }

    private fun rimProfile(raw: Int): RimProfile? = when (raw) {
        0 -> RimProfile.TORIC
        1 -> RimProfile.SMOOTH
        2 -> RimProfile.DROPLET
        3 -> RimProfile.LEGACY
        else -> null
    }
}
