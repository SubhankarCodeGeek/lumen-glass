package com.securnyx360.lumenglass.core

import kotlin.math.max
import kotlin.math.min

/**
 * How the glass surface displaces (refracts) the content behind it.
 *
 * This is the **headline differentiator** of Lumen Glass. Every other open-source Android glass
 * implementation found is *rim-only* — the lens bends content near the surface edge and leaves the
 * middle flat. Lumen Glass supports both models, and a **blend** of the two.
 *
 * The model is pure data + pure maths (no graphics types), so it lives in `core/` and is unit
 * tested on the JVM. A graphics backend resolves a [LensModel] into the weight field its shader
 * consumes.
 *
 * @see resolveWeights
 */
sealed interface LensModel {

    /**
     * Centre-weighted lens — content is magnified/warped across the **whole face**, including the
     * exact centre (a "solid glass slab" read).
     *
     * @param weight strength, `0f`..`1f` (`0f` disables the centre term).
     */
    data class Centre(val weight: Float) : LensModel

    /**
     * Rim-weighted lens — content bends only within a band near the edge; the middle is untouched.
     * This is the behaviour of the incumbent libraries.
     *
     * @param weight strength, `0f`..`1f` (`0f` disables the rim term).
     */
    data class Rim(val weight: Float) : LensModel

    /**
     * Blend of [Centre] and [Rim] — the thing neither incumbent offers.
     *
     * @param centre centre-term weight, `0f`..`1f`.
     * @param rim rim-term weight, `0f`..`1f`.
     */
    data class Blend(val centre: Float, val rim: Float) : LensModel
}

/**
 * The resolved lens weights a backend feeds to its shader.
 *
 * @property centre weight of the whole-face (centre-weighted) term, clamped to `0f`..`1f`.
 * @property rim weight of the edge (rim-weighted) term, clamped to `0f`..`1f`.
 */
data class LensWeights(val centre: Float, val rim: Float)

/**
 * Resolves a [LensModel] into clamped [LensWeights].
 *
 * All inputs are clamped to `0f`..`1f` so a backend never receives an out-of-range uniform
 * (shader uniforms misbehaving on bad input is a real, hard-to-debug failure mode).
 */
fun LensModel.resolveWeights(): LensWeights = when (this) {
    is LensModel.Centre -> LensWeights(centre = weight.coerceIn01(), rim = 0f)
    is LensModel.Rim -> LensWeights(centre = 0f, rim = weight.coerceIn01())
    is LensModel.Blend -> LensWeights(centre = centre.coerceIn01(), rim = rim.coerceIn01())
}

private fun Float.coerceIn01(): Float = max(0f, min(1f, this))
