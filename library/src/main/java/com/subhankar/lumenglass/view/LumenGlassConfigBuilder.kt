/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import com.subhankar.lumenglass.core.Bezel
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.LensModel
import com.subhankar.lumenglass.core.RimProfile

/**
 * A mutable, View-friendly builder for [GlassSpec].
 *
 * ## Why this exists next to the Compose DSL
 *
 * The Compose API builds a spec through `GlassSpecBuilder`, whose properties are `Dp` and
 * `androidx.compose.ui.graphics.Color`. A View API cannot depend on Compose types (the `view/`
 * package has no Compose imports), and XML has no `Dp`. This builder therefore takes **dp as
 * `Float`** and colours as `@ColorInt` `Int` — the two representations XML and Java callers already
 * use.
 *
 * It is deliberately a thin shell: [build] is the only conversion, and the result is the same
 * `GlassSpec` the Compose API produces, so both backends share one rendering policy
 * (`resolveGlassRenderPlan`).
 *
 * ```kotlin
 * lumenGlassView.setGlassConfig {
 *     lens = LensModel.Blend(centre = 0.6f, rim = 0.4f)
 *     frostDp = 8f
 *     cornerRadiusDp = 24f
 *     tintArgb = 0x33FFFFFF
 *     bezel(radiusDp = 10f, alpha = 0.14f)
 * }
 * ```
 *
 * ## Units
 *
 * - `*Dp` properties are **dp**, not px. The renderer multiplies by display density exactly once.
 * - [tintArgb] carries its own alpha; `0` is fully transparent, i.e. no tint.
 *
 * Invalid values are clamped by [build] (negative lengths, out-of-range alpha) rather than reaching
 * the shader or the platform blur, both of which can throw on nonsensical input. Lens weights are
 * clamped when the uniforms are resolved, as in every other backend.
 */
public class LumenGlassConfigBuilder {

    /** The lens model. Defaults to [GlassSpec.Default]'s blend. */
    public var lens: LensModel = GlassSpec.Default.lens

    /** Overall displacement strength. */
    public var refraction: Float = GlassSpec.Default.refraction

    /** Chromatic-aberration amount; may be disabled by the resolved quality tier. */
    public var dispersion: Float = GlassSpec.Default.dispersion

    /** Blur radius in dp; `0f` disables the blur. */
    public var frostDp: Float = GlassSpec.Default.frostDp

    /** Material tint, ARGB (expects its own alpha; `0` = none). */
    public var tintArgb: Int = GlassSpec.Default.tintArgb

    /** Colour saturation multiplier (`1f` = unchanged). */
    public var saturation: Float = GlassSpec.Default.saturation

    /** Colour contrast multiplier (`1f` = unchanged). */
    public var contrast: Float = GlassSpec.Default.contrast

    /** Specular edge-band strength, `0f`..`1f`. */
    public var edgeLight: Float = GlassSpec.Default.edgeLight

    /** Corner radius in dp; `0f` = sharp rectangle. */
    public var cornerRadiusDp: Float = GlassSpec.Default.cornerRadiusDp

    /** `true` runs the calibrated precision path; `false` runs legacy diffuse mode. */
    public var precisionMode: Boolean = GlassSpec.Default.precisionMode

    /** Geometric curvature profile for the rim bend. */
    public var rimProfile: RimProfile = GlassSpec.Default.rimProfile

    /**
     * Bezel inner-shadow radius in dp, or `null` for no bezel.
     *
     * `null` is the default because [GlassSpec] treats a bezel as optional; passing `0f` would
     * produce a zero-width shadow rather than none, which is not the same thing.
     */
    public var bezelRadiusDp: Float? = null

    /** Bezel alpha, `0f`..`1f`; only used when [bezelRadiusDp] is non-null. */
    public var bezelAlpha: Float = 0.12f

    /** Configures a bezel in one call. */
    public fun bezel(radiusDp: Float = 12f, alpha: Float = 0.12f) {
        bezelRadiusDp = radiusDp
        bezelAlpha = alpha
    }

    /**
     * Resolves this builder into an immutable [GlassSpec].
     *
     * Clamping keeps a bad call from crashing a draw: a negative blur radius throws inside
     * `RenderEffect.createBlurEffect`, and a negative corner radius is meaningless to the shader.
     */
    public fun build(): GlassSpec = GlassSpec(
        lens = lens,
        refraction = refraction.coerceAtLeast(0f),
        dispersion = dispersion.coerceAtLeast(0f),
        frostDp = frostDp.coerceAtLeast(0f),
        tintArgb = tintArgb,
        saturation = saturation.coerceAtLeast(0f),
        contrast = contrast.coerceAtLeast(0f),
        edgeLight = edgeLight.coerceIn(0f, 1f),
        bezel = bezelRadiusDp?.let { radiusDp ->
            Bezel(
                radiusDp = radiusDp.coerceAtLeast(0f),
                alpha = bezelAlpha.coerceIn(0f, 1f),
            )
        },
        cornerRadiusDp = cornerRadiusDp.coerceAtLeast(0f),
        precisionMode = precisionMode,
        rimProfile = rimProfile,
    )
}
