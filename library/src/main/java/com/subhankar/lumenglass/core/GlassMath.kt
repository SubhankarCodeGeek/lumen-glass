/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure mathematics behind the glass shader.
 *
 * This object is the **specification** for the glass algorithm. The AGSL shader is corrected to
 * match it, and `ShaderMathParityTest` (instrumented, in `:app`) enforces the agreement numerically
 * by rendering a diagnostic probe and comparing its readback against these functions.
 *
 * Keeping the maths here means it is **unit-tested on the JVM**, whereas the shader itself can only
 * be compiled and rendered on a device. The shader is therefore a transliteration of *tested*
 * behaviour rather than an untested blob — and, since Q-F, that claim is **enforced by a test**
 * rather than merely asserted.
 *
 * > **History (Q-F):** before that test existed, the shader and this object had silently drifted
 * > apart — they used different lens scale terms and *fundamentally different* dispersion
 * > formulations. The KDoc previously claimed they mirrored each other; nothing verified it, and it
 * > was not true. The drift is fixed and the claim is now backed by a device test.
 *
 * ## Clean-room note
 *
 * These formulas are derived from **generic, public computer-graphics references** (2D signed
 * distance functions; separable blur; per-channel radial scaling) and from our own analysis. No
 * source text was copied from any third-party glass shader — see `CLEANROOM.md`.
 *
 * ## Coordinate convention
 *
 * - Positions and sizes here are in **the same space** the caller uses (pixels or normalized units)
 *   as long as they are consistent.
 * - `nx`/`ny` are **normalized to `-1..1` per axis** (i.e. `p / halfSize`), which is what makes the
 *   maths independent of the surface's aspect ratio.
 * - Half-extents are in **the same units as the positions** — in the shader, pixels.
 */
object GlassMath {

    /**
     * Signed distance to a rounded rectangle, `<= 0` inside and `> 0` outside.
     *
     * Standard 2D rounded-box SDF: subtract the corner radius from the half-extents, clamp the
     * negative region for the interior, and add the length of the positive overshoot minus the
     * radius.
     *
     * @param px point X, relative to the rectangle centre.
     * @param py point Y, relative to the rectangle centre.
     * @param halfWidth half the rectangle width (before rounding).
     * @param halfHeight half the rectangle height (before rounding).
     * @param radius corner radius.
     */
    fun roundedRectSdf(
        px: Float,
        py: Float,
        halfWidth: Float,
        halfHeight: Float,
        radius: Float,
    ): Float {
        val r = min(radius, min(halfWidth, halfHeight)).coerceAtLeast(0f)
        val qx = abs(px) - (halfWidth - r)
        val qy = abs(py) - (halfHeight - r)
        val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f))
        val inside = min(max(qx, qy), 0f)
        return outside + inside - r
    }

    /**
     * Aspect-agnostic "squircle" distance, `0` at the centre and ramping to ~`1` at the border.
     *
     * The reference implementations we studied multiply the X term by the surface aspect ratio and
     * by a large constant, which **collapses to zero on non-square surfaces** (e.g. a wide banner)
     * and silently disables the lens. Lumen Glass instead normalizes each axis to `-1..1` and takes
     * a superellipse norm, so the field is **shape-independent**.
     *
     * @param nx normalized X in `-1..1`.
     * @param ny normalized Y in `-1..1`.
     * @param exponent superellipse hardness (higher = squarer). `2f` = ellipse; `8f` ≈ squircle.
     */
    fun squircle(nx: Float, ny: Float, exponent: Float): Float {
        val e = max(exponent, 1e-3f)
        return powAbs(nx, e) + powAbs(ny, e)
    }

    /**
     * Lens sample displacement for a pixel, given the two lens weights.
     *
     * The displacement pulls the sample **toward the centre**:
     * - the **centre** term weights by `1 - squircle` (max at the middle, zero at the rim);
     * - the **rim** term weights by `squircle` (zero at the middle, max at the rim).
     *
     * So `centreWeight = 1, rimWeight = 0` warps the whole face; `centre = 0, rim = 1` bends only
     * the edge (the incumbent behaviour); any blend is in between — the differentiator.
     *
     * ## The scale term (Q-I)
     *
     * The displacement is scaled by **`min(halfWidth, halfHeight)`**, not by either half-extent
     * alone. This takes the same half-extents as [roundedRectSdf] and derives the scale internally,
     * so there is **no undocumented caller convention** and the shader and this function are
     * literally the same formula. (Previously this took a bare `halfExtent` scalar, which callers
     * had to know to pass the minimum for — trivially easy to get wrong, and exactly the kind of
     * silent drift Q-F found.)
     *
     * @param nx normalized X in `-1..1`.
     * @param ny normalized Y in `-1..1`.
     * @param squircleValue the squircle field at this point (`0` centre … `~1` rim).
     * @param halfWidth half the surface width, in the same units as the result.
     * @param halfHeight half the surface height, in the same units as the result.
     * @return the sample offset `(dx, dy)` to add to the pixel position, in the same units.
     */
    fun lensOffset(
        nx: Float,
        ny: Float,
        squircleValue: Float,
        centreWeight: Float,
        rimWeight: Float,
        refraction: Float,
        halfWidth: Float,
        halfHeight: Float,
    ): FloatArray {
        val sq = squircleValue.coerceIn(0f, 1f)
        val centreTerm = (1f - sq) * centreWeight.coerceIn(0f, 1f)
        val rimTerm = sq * rimWeight.coerceIn(0f, 1f)
        val weight = (centreTerm + rimTerm) * refraction
        val scale = min(halfWidth, halfHeight)
        return floatArrayOf(-nx * weight * scale, -ny * weight * scale)
    }

    /**
     * Per-channel dispersion scales (chromatic aberration).
     *
     * Thick glass refracts different wavelengths by slightly different amounts, producing a colour
     * fringe. We model this as a **radial scale about the centre**, which is the physically correct
     * model: the effect is *zero at the optical centre* and grows toward the rim.
     *
     * Returns `(redScale, greenScale, blueScale)`. To apply it, scale the centre-relative
     * coordinate by each channel's factor, sample, and recombine `(R from the red tap, G from the
     * green tap, B from the blue tap)`.
     *
     * ## This is the authoritative formulation (Q-H)
     *
     * The shader previously used a **different** formulation — a linear offset rather than a radial
     * scale — and nothing tied the two together. That drift is exactly what Q-F uncovered; the
     * shader now implements *this* function, and `ShaderMathParityTest` enforces the agreement on
     * device. Do not reintroduce an offset-based variant.
     *
     * At `dispersion = 0` all three scales are identical (`1`), so the shader collapses to a single
     * sample — which is why it gates the multi-tap path behind both a non-zero dispersion and the
     * HIGH tier.
     *
     * @param dispersion strength; `0f` returns all-equal scales (no fringe). Values `>= 1` would
     *   invert a channel and are clamped by the caller's `GlassSpec` contract, not here.
     */
    fun dispersionScales(dispersion: Float): FloatArray {
        val d = dispersion.coerceAtLeast(0f)
        return floatArrayOf(1f + d, 1f, 1f - d)
    }

    private fun powAbs(base: Float, exponent: Float): Float {
        val a = abs(base)
        // Integer-friendly fast path is unnecessary here; keep the general form for clarity.
        return if (a == 0f) 0f else expApprox(a, exponent)
    }

    /** `a^e` for `a >= 0`, `e > 0`. Uses ln/exp; kept tiny and dependency-free. */
    private fun expApprox(a: Float, e: Float): Float =
        kotlin.math.exp(e * kotlin.math.ln(a))
}
