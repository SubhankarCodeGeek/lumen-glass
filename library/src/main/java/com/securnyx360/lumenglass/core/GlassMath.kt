package com.securnyx360.lumenglass.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure mathematics behind the glass shader.
 *
 * These are the formulas the AGSL shader mirrors. Keeping them here means the algorithm is
 * **unit-tested on the JVM** (the shader itself can only be compiled/rendered on a device) and the
 * shader is a transliteration of *tested* behaviour rather than an untested blob.
 *
 * ## Clean-room note
 *
 * These formulas are derived from **generic, public computer-graphics references** (2D signed
 * distance functions; separable blur; per-channel sample offsets) and from our own analysis. No
 * source text was copied from any third-party glass shader — see `CLEANROOM.md`.
 *
 * ## Coordinate convention
 *
 * Everywhere here, positions and sizes are in **the same space** the caller uses (pixels or
 * normalized units) as long as they are consistent. The shader normalizes to a `-1..1` per-axis
 * space so the maths is independent of the surface's aspect ratio.
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
     * @return the sample offset `(dx, dy)` to add to the pixel position, in the same units.
     */
    fun lensOffset(
        nx: Float,
        ny: Float,
        squircleValue: Float,
        centreWeight: Float,
        rimWeight: Float,
        refraction: Float,
        halfExtent: Float,
    ): FloatArray {
        val sq = squircleValue.coerceIn(0f, 1f)
        val centreTerm = (1f - sq) * centreWeight.coerceIn(0f, 1f)
        val rimTerm = sq * rimWeight.coerceIn(0f, 1f)
        val weight = (centreTerm + rimTerm) * refraction
        return floatArrayOf(-nx * weight * halfExtent, -ny * weight * halfExtent)
    }

    /**
     * Per-channel dispersion offsets (chromatic aberration).
     *
     * Red and blue are sampled at slightly different radial scales than green, which is what
     * produces the colour fringe of thick glass. Returns `(redScale, greenScale, blueScale)`.
     *
     * @param dispersion strength; `0f` returns all-equal scales (no fringe).
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
