/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.android

import com.subhankar.lumenglass.core.GlassFeatures
import com.subhankar.lumenglass.core.GlassSpec

/**
 * The resolved GPU inputs for one glass surface.
 *
 * @property uniforms the fully-bound shader inputs, in shader space.
 * @property frostRadiusPx blur radius in px, or `0f` when the resolved features run no blur.
 */
internal data class GlassRenderPlan(
    val uniforms: GlassUniforms,
    val frostRadiusPx: Float,
)

/**
 * Resolves a [GlassSpec] plus a [GlassFeatures] into a [GlassRenderPlan].
 *
 * This is the **single** place the feature-to-uniform policy lives. Both the Compose backend
 * (`LumenGlassNode.draw`) and the future View backend call it, so the two cannot drift into
 * different materials for the same spec and tier.
 *
 * ## Units
 *
 * [GlassSpec] stores dp: the Compose DSL converts its `Dp` fields at the API boundary with density
 * `1f`, so the values in the spec are still dp. The renderer multiplies by [density] **exactly
 * once**, here. A caller must therefore pass the raw spec and the real density — pre-scaling
 * either would double-scale.
 *
 * ## Gating
 *
 * Dispersion and the tier uniform follow [GlassFeatures.useDispersion]; the bezel radius follows
 * [GlassFeatures.useBezel]. Bezel **alpha** gating deliberately stays inside [GlassUniforms.from]
 * (HIGH only), so a spec that asks for a bezel cannot draw one on a cheaper tier even if a caller
 * passes a non-zero radius.
 *
 * @param spec the material description, with dp-valued fields unscaled.
 * @param widthPx surface width in px.
 * @param heightPx surface height in px.
 * @param density the current display density, applied once to the dp-valued fields.
 * @param features the effect set the resolved quality tier enables.
 * @return the uniforms and frost radius to hand to the effect builder.
 */
internal fun resolveGlassRenderPlan(
    spec: GlassSpec,
    widthPx: Float,
    heightPx: Float,
    density: Float,
    features: GlassFeatures,
): GlassRenderPlan {
    val dispersion = if (features.useDispersion) spec.dispersion else 0f
    val tier = if (features.useDispersion) TIER_HIGH else TIER_BALANCED
    val cornerRadiusPx = spec.cornerRadiusDp * density
    val bezelRadiusPx = if (features.useBezel) (spec.bezel?.radiusDp ?: 0f) * density else 0f

    val uniforms = GlassUniforms.from(
        spec = spec,
        widthPx = widthPx,
        heightPx = heightPx,
        cornerRadiusPx = cornerRadiusPx,
        dispersion = dispersion,
        refraction = spec.refraction,
        tier = tier,
        bezelRadiusPx = bezelRadiusPx,
    )

    return GlassRenderPlan(
        uniforms = uniforms,
        frostRadiusPx = if (features.useBlur) spec.frostDp * density else 0f,
    )
}
