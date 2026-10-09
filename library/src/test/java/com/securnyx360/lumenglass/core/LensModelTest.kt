package com.securnyx360.lumenglass.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for [LensModel] weight resolution + clamping. */
class LensModelTest {

    @Test
    fun centre_resolvesToCentreOnly() {
        val w = LensModel.Centre(0.7f).resolveWeights()
        assertEquals(0.7f, w.centre, 1e-6f)
        assertEquals(0f, w.rim, 1e-6f)
    }

    @Test
    fun rim_resolvesToRimOnly() {
        val w = LensModel.Rim(0.4f).resolveWeights()
        assertEquals(0f, w.centre, 1e-6f)
        assertEquals(0.4f, w.rim, 1e-6f)
    }

    @Test
    fun blend_resolvesBoth() {
        val w = LensModel.Blend(centre = 0.6f, rim = 0.4f).resolveWeights()
        assertEquals(0.6f, w.centre, 1e-6f)
        assertEquals(0.4f, w.rim, 1e-6f)
    }

    @Test
    fun weights_areClampedToUnitRange() {
        val w = LensModel.Blend(centre = 5f, rim = -3f).resolveWeights()
        assertEquals(1f, w.centre, 1e-6f)
        assertEquals(0f, w.rim, 1e-6f)
    }
}
