/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import com.subhankar.lumenglass.core.Bezel
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.LensModel
import com.subhankar.lumenglass.core.RimProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the View-side configuration DSL.
 *
 * The builder exists so the View backend can be configured without Compose types; these tests pin
 * that it produces exactly the same [GlassSpec] the Compose DSL would, and that out-of-range values
 * cannot reach the shader or the platform blur.
 */
class LumenGlassConfigBuilderTest {

    @Test
    fun defaults_matchGlassSpecDefault() {
        assertEquals(GlassSpec.Default, LumenGlassConfigBuilder().build())
    }

    @Test
    fun bezel_isOptIn() {
        assertNull(LumenGlassConfigBuilder().build().bezel)

        val withBezel = LumenGlassConfigBuilder().apply {
            bezel(radiusDp = 10f, alpha = 0.2f)
        }.build()

        assertEquals(Bezel(radiusDp = 10f, alpha = 0.2f), withBezel.bezel)
    }

    @Test
    fun lensVariants_arePreserved() {
        val spec = LumenGlassConfigBuilder().apply {
            lens = LensModel.Rim(weight = 0.35f)
        }.build()

        assertEquals(LensModel.Rim(weight = 0.35f), spec.lens)
    }

    @Test
    fun build_clampsOutOfRangeValues() {
        val spec = LumenGlassConfigBuilder().apply {
            refraction = -1f
            dispersion = -1f
            frostDp = -8f
            saturation = -1f
            contrast = -1f
            edgeLight = 2f
            cornerRadiusDp = -4f
            bezel(radiusDp = -2f, alpha = 2f)
        }.build()

        assertEquals(0f, spec.refraction)
        assertEquals(0f, spec.dispersion)
        assertEquals(0f, spec.frostDp)
        assertEquals(0f, spec.saturation)
        assertEquals(0f, spec.contrast)
        assertEquals(1f, spec.edgeLight)
        assertEquals(0f, spec.cornerRadiusDp)
        assertEquals(0f, spec.bezel?.radiusDp)
        assertEquals(1f, spec.bezel?.alpha)
    }

    @Test
    fun everyScalarField_mapsThrough() {
        val spec = LumenGlassConfigBuilder().apply {
            refraction = 1.4f
            dispersion = 0.15f
            frostDp = 9f
            tintArgb = 0x33FFFFFF
            saturation = 1.1f
            contrast = 0.95f
            edgeLight = 0.3f
            cornerRadiusDp = 18f
            precisionMode = false
            rimProfile = RimProfile.SMOOTH
            bezel(radiusDp = 7f, alpha = 0.1f)
        }.build()

        assertEquals(1.4f, spec.refraction)
        assertEquals(0.15f, spec.dispersion)
        assertEquals(9f, spec.frostDp)
        assertEquals(0x33FFFFFF, spec.tintArgb)
        assertEquals(1.1f, spec.saturation)
        assertEquals(0.95f, spec.contrast)
        assertEquals(0.3f, spec.edgeLight)
        assertEquals(18f, spec.cornerRadiusDp)
        assertEquals(false, spec.precisionMode)
        assertEquals(RimProfile.SMOOTH, spec.rimProfile)
        assertEquals(Bezel(radiusDp = 7f, alpha = 0.1f), spec.bezel)
    }
}
