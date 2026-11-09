/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import com.subhankar.lumenglass.core.Bezel
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.LensModel
import com.subhankar.lumenglass.core.QualityTier
import com.subhankar.lumenglass.core.RimProfile

/**
 * The XML lens selector, as a platform-free enum.
 *
 * The `TypedArray` reader converts the enum's integer value to this type, so the mapping to
 * [LensModel] is JVM-testable without an `AttributeSet`.
 */
internal enum class LensModelTag { CENTRE, RIM, BLEND }

/**
 * The values read from a `LumenGlassView` styleable, with `null` meaning **attribute was not set**.
 *
 * `null` is the important part: an unset attribute must leave the base [GlassSpec] alone. If the
 * reader substituted (say) `0f` for an unset frost radius, every absent attribute would silently
 * override the defaults — the "attribute defaults overriding named presets" failure from the
 * roadmap.
 *
 * Everything here is already in the units [GlassSpec] stores: dp (the reader divides px by
 * density) and ARGB.
 */
internal data class GlassAttrValues(
    val lensModel: LensModelTag? = null,
    val centreWeight: Float? = null,
    val rimWeight: Float? = null,
    val refraction: Float? = null,
    val dispersion: Float? = null,
    val frostDp: Float? = null,
    val tintArgb: Int? = null,
    val saturation: Float? = null,
    val contrast: Float? = null,
    val edgeLight: Float? = null,
    val cornerRadiusDp: Float? = null,
    val precisionMode: Boolean? = null,
    val rimProfile: RimProfile? = null,
    val bezelRadiusDp: Float? = null,
    val bezelAlpha: Float? = null,
)

/**
 * Everything the `LumenGlassView` constructor needs from its XML attributes.
 *
 * @property values the material attributes.
 * @property enabled `lumenGlassEnabled` (default `true`).
 * @property quality the parsed `lumenGlassQuality` override, or `null` for capability selection.
 * @property sourceId the `lumenGlassSource` reference, or `null` when unset.
 */
internal data class ParsedGlassAttributes(
    val values: GlassAttrValues,
    val enabled: Boolean,
    val quality: QualityTier?,
    val sourceId: Int?,
)

/** The blend weights used when a lens attribute sets a model but not a weight. */
private const val DEFAULT_CENTRE_WEIGHT: Float = 0.6f
private const val DEFAULT_RIM_WEIGHT: Float = 0.4f

/**
 * Resolves the attribute values onto [base], leaving every unset field untouched.
 *
 * Pure: no Android types. Unit-tested by `GlassAttrValuesTest`, which is where the clamp and
 * `null`-preservation contracts are pinned down.
 *
 * ### Lens weights
 *
 * Weights are **per variant**, not one universal "centre" field:
 * - [LensModelTag.CENTRE] uses [GlassAttrValues.centreWeight] (`0.6` when unset).
 * - [LensModelTag.RIM] uses [GlassAttrValues.rimWeight] (`0.4` when unset).
 * - [LensModelTag.BLEND], or no model at all (when a weight was still supplied), uses both.
 *
 * Weights clamp to `0f`..`1f`; lengths to `>= 0`; [GlassSpec.edgeLight] and the bezel alpha to
 * `0f`..`1f`. Invalid values must degrade, not crash a draw — the same contract as
 * [LumenGlassConfigBuilder.build].
 *
 * ### Bezel
 *
 * `null` is preserved when neither bezel attribute is present, because [GlassSpec] treats a bezel
 * as optional. When only one of radius/alpha is supplied, the other comes from [base]'s bezel (or
 * the [Bezel] defaults), so a partial XML override does not erase the preset's bezel.
 */
internal fun GlassAttrValues.toGlassSpec(base: GlassSpec = GlassSpec.Default): GlassSpec = base.copy(
    lens = resolveLens(base.lens),
    refraction = (refraction ?: base.refraction).coerceAtLeast(0f),
    dispersion = (dispersion ?: base.dispersion).coerceAtLeast(0f),
    frostDp = (frostDp ?: base.frostDp).coerceAtLeast(0f),
    tintArgb = tintArgb ?: base.tintArgb,
    saturation = (saturation ?: base.saturation).coerceAtLeast(0f),
    contrast = (contrast ?: base.contrast).coerceAtLeast(0f),
    edgeLight = (edgeLight ?: base.edgeLight).coerceIn(0f, 1f),
    bezel = resolveBezel(base.bezel),
    cornerRadiusDp = (cornerRadiusDp ?: base.cornerRadiusDp).coerceAtLeast(0f),
    precisionMode = precisionMode ?: base.precisionMode,
    rimProfile = rimProfile ?: base.rimProfile,
)

private fun GlassAttrValues.resolveLens(base: LensModel): LensModel {
    if (lensModel == null && centreWeight == null && rimWeight == null) return base

    val centre = (centreWeight ?: DEFAULT_CENTRE_WEIGHT).coerceIn(0f, 1f)
    val rim = (rimWeight ?: DEFAULT_RIM_WEIGHT).coerceIn(0f, 1f)
    return when (lensModel) {
        LensModelTag.CENTRE -> LensModel.Centre(centre)
        LensModelTag.RIM -> LensModel.Rim(rim)
        LensModelTag.BLEND, null -> LensModel.Blend(centre = centre, rim = rim)
    }
}

private fun GlassAttrValues.resolveBezel(base: Bezel?): Bezel? {
    if (bezelRadiusDp == null && bezelAlpha == null) return base

    return Bezel(
        radiusDp = (bezelRadiusDp ?: base?.radiusDp ?: 12f).coerceAtLeast(0f),
        offsetYDp = base?.offsetYDp ?: 4f,
        alpha = (bezelAlpha ?: base?.alpha ?: 0.12f).coerceIn(0f, 1f),
    )
}
