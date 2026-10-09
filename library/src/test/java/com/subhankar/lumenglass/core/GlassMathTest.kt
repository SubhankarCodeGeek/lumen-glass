package com.subhankar.lumenglass.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure glass mathematics ([GlassMath]).
 *
 * These are the *tested* definition of the algorithm; the AGSL shader mirrors them. Because AGSL can
 * only be compiled/rendered on a device, this is where the maths gets real coverage.
 */
class GlassMathTest {

    // ---- roundedRectSdf -----------------------------------------------------

    @Test
    fun roundedRectSdf_isNegativeAtCentre() {
        val d = GlassMath.roundedRectSdf(0f, 0f, halfWidth = 100f, halfHeight = 50f, radius = 14f)
        assertTrue("centre must be inside (d <= 0) but was $d", d <= 0f)
    }

    @Test
    fun roundedRectSdf_isPositiveOutside() {
        val d = GlassMath.roundedRectSdf(200f, 0f, halfWidth = 100f, halfHeight = 50f, radius = 14f)
        assertTrue("far point must be outside (d > 0) but was $d", d > 0f)
    }

    @Test
    fun roundedRectSdf_isZeroOnEdge() {
        // On the right edge, x == halfWidth, y == 0 (away from the rounded corner).
        val d = GlassMath.roundedRectSdf(100f, 0f, halfWidth = 100f, halfHeight = 50f, radius = 14f)
        assertEquals("edge point should be ~0", 0f, d, 1e-3f)
    }

    @Test
    fun roundedRectSdf_isSymmetric() {
        val a = GlassMath.roundedRectSdf(30f, -20f, 100f, 50f, 14f)
        val b = GlassMath.roundedRectSdf(-30f, 20f, 100f, 50f, 14f)
        assertEquals(a, b, 1e-5f)
    }

    // ---- squircle -----------------------------------------------------------

    @Test
    fun squircle_isZeroAtCentre() {
        assertEquals(0f, GlassMath.squircle(0f, 0f, exponent = 8f), 1e-6f)
    }

    @Test
    fun squircle_isOneOnAxisBorder() {
        // At the border on the X axis, n = (1, 0) -> 1^e + 0^e = 1.
        assertEquals(1f, GlassMath.squircle(1f, 0f, exponent = 8f), 1e-4f)
        assertEquals(1f, GlassMath.squircle(0f, 1f, exponent = 8f), 1e-4f)
    }

    // ---- lensOffset ---------------------------------------------------------

    @Test
    fun lensOffset_isZeroAtExactCentre() {
        val o = GlassMath.lensOffset(
            nx = 0f, ny = 0f,
            squircleValue = 0f,
            centreWeight = 1f, rimWeight = 1f,
            refraction = 1f, halfWidth = 100f, halfHeight = 100f,
        )
        assertEquals(0f, o[0], 1e-5f)
        assertEquals(0f, o[1], 1e-5f)
    }

    @Test
    fun lensOffset_centreWeightWrapsWholeFace() {
        // With centreWeight only, a mid-surface pixel is displaced.
        val o = GlassMath.lensOffset(
            nx = 0.5f, ny = 0f,
            squircleValue = GlassMath.squircle(0.5f, 0f, 8f),
            centreWeight = 1f, rimWeight = 0f,
            refraction = 1f, halfWidth = 100f, halfHeight = 100f,
        )
        assertTrue("centre-weighted lens should displace mid-surface pixels", o[0] != 0f)
    }

    @Test
    fun lensOffset_rimWeightIsZeroInTheMiddle() {
        // With rimWeight only, at the centre the squircle term is 0 -> no displacement.
        val o = GlassMath.lensOffset(
            nx = 0f, ny = 0f,
            squircleValue = 0f,
            centreWeight = 0f, rimWeight = 1f,
            refraction = 1f, halfWidth = 100f, halfHeight = 100f,
        )
        assertEquals(0f, o[0], 1e-5f)
        assertEquals(0f, o[1], 1e-5f)
    }

    @Test
    fun lensOffset_isZeroWhenRefractionIsZero() {
        val o = GlassMath.lensOffset(
            0.7f, 0.7f, 0.5f, 1f, 1f,
            refraction = 0f, halfWidth = 100f, halfHeight = 100f,
        )
        assertEquals(0f, o[0], 1e-5f)
        assertEquals(0f, o[1], 1e-5f)
    }

    @Test
    fun lensOffset_pointsTowardCentre() {
        // Displacement is negative of the outward direction => samples toward the centre.
        val o = GlassMath.lensOffset(
            1f, 0f, 1f, 1f, 1f,
            refraction = 1f, halfWidth = 100f, halfHeight = 100f,
        )
        assertTrue("x offset should be negative (toward centre)", o[0] < 0f)
    }

    // ---- lensOffset scale term (Q-I) ----------------------------------------

    @Test
    fun lensOffset_scalesByTheSmallerHalfExtent_onNonSquareSurfaces() {
        // The scale term is min(halfWidth, halfHeight). On a wide surface the height is the
        // smaller extent, so that is what bounds the displacement.
        val wide = GlassMath.lensOffset(
            1f, 0f, 1f, 1f, 1f,
            refraction = 1f, halfWidth = 500f, halfHeight = 100f,
        )
        // weight = ((1 - 1) * 1 + 1 * 1) * 1 = 1, scale = min(500, 100) = 100
        assertEquals(-100f, wide[0], 1e-3f)

        val tall = GlassMath.lensOffset(
            1f, 0f, 1f, 1f, 1f,
            refraction = 1f, halfWidth = 100f, halfHeight = 500f,
        )
        assertEquals(
            "A tall surface must scale by its width (the smaller extent)",
            -100f, tall[0], 1e-3f,
        )
    }

    @Test
    fun lensOffset_isAspectIndependentForTheScaleTerm() {
        // Transposing the surface halves must transpose the bound displacement: this is the
        // property the shader's min() and this function must agree on.
        val a = GlassMath.lensOffset(1f, 0f, 1f, 1f, 1f, 1f, halfWidth = 400f, halfHeight = 120f)
        val b = GlassMath.lensOffset(1f, 0f, 1f, 1f, 1f, 1f, halfWidth = 120f, halfHeight = 400f)
        assertEquals(a[0], b[0], 1e-3f)
    }

    // ---- dispersionScales ---------------------------------------------------

    @Test
    fun dispersionScales_areEqualWhenOff() {
        val s = GlassMath.dispersionScales(0f)
        assertEquals(1f, s[0], 1e-6f)
        assertEquals(1f, s[1], 1e-6f)
        assertEquals(1f, s[2], 1e-6f)
    }

    @Test
    fun dispersionScales_splitRedAndBlue() {
        val s = GlassMath.dispersionScales(0.2f)
        assertTrue("red scale > green", s[0] > s[1])
        assertTrue("blue scale < green", s[2] < s[1])
        assertEquals(1f, s[1], 1e-6f)
    }

    @Test
    fun dispersionScales_clampNegativeToZero() {
        val s = GlassMath.dispersionScales(-5f)
        assertEquals(1f, s[0], 1e-6f)
        assertEquals(1f, s[2], 1e-6f)
    }
}
