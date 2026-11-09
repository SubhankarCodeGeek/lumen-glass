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
 * JVM tests for the pure XML-attribute → [GlassSpec] mapper.
 *
 * The two contracts that matter most:
 *
 * 1. **Unset means untouched.** An absent attribute must not overwrite a default or a preset; that
 *    is why [GlassAttrValues] uses nulls rather than sentinel primitives.
 * 2. **Invalid values clamp, never crash.** XML can carry a negative dimension or an out-of-range
 *    weight, and a draw must degrade rather than throw.
 */
class GlassAttrValuesTest {

    @Test
    fun emptyValues_leaveTheDefaultSpecUntouched() {
        assertEquals(GlassSpec.Default, GlassAttrValues().toGlassSpec())
    }

    @Test
    fun emptyValues_preserveAPresetBase() {
        assertEquals(GlassSpec.AppleLight, GlassAttrValues().toGlassSpec(GlassSpec.AppleLight))
        assertEquals(GlassSpec.AppleDark, GlassAttrValues().toGlassSpec(GlassSpec.AppleDark))
    }

    @Test
    fun blendModel_usesBothWeights() {
        val spec = GlassAttrValues(
            lensModel = LensModelTag.BLEND,
            centreWeight = 0.25f,
            rimWeight = 0.75f,
        ).toGlassSpec()

        assertEquals(LensModel.Blend(centre = 0.25f, rim = 0.75f), spec.lens)
    }

    @Test
    fun centreModel_usesTheCentreWeightAndIgnoresTheRimWeight() {
        val spec = GlassAttrValues(
            lensModel = LensModelTag.CENTRE,
            centreWeight = 0.2f,
            rimWeight = 0.9f,
        ).toGlassSpec()

        assertEquals(LensModel.Centre(weight = 0.2f), spec.lens)
    }

    @Test
    fun rimModel_usesTheRimWeightAndIgnoresTheCentreWeight() {
        val spec = GlassAttrValues(
            lensModel = LensModelTag.RIM,
            centreWeight = 0.9f,
            rimWeight = 0.3f,
        ).toGlassSpec()

        assertEquals(LensModel.Rim(weight = 0.3f), spec.lens)
    }

    @Test
    fun weightsWithoutAModel_fallBackToBlend() {
        val spec = GlassAttrValues(centreWeight = 0.1f, rimWeight = 0.2f).toGlassSpec()

        assertEquals(LensModel.Blend(centre = 0.1f, rim = 0.2f), spec.lens)
    }

    @Test
    fun weights_clampToUnitRange() {
        val spec = GlassAttrValues(
            lensModel = LensModelTag.BLEND,
            centreWeight = 2f,
            rimWeight = -1f,
        ).toGlassSpec()

        assertEquals(LensModel.Blend(centre = 1f, rim = 0f), spec.lens)
    }

    @Test
    fun modelWithoutAWeight_usesTheDocumentedDefault() {
        val centre = GlassAttrValues(lensModel = LensModelTag.CENTRE).toGlassSpec()
        val rim = GlassAttrValues(lensModel = LensModelTag.RIM).toGlassSpec()

        assertEquals(LensModel.Centre(weight = 0.6f), centre.lens)
        assertEquals(LensModel.Rim(weight = 0.4f), rim.lens)
    }

    @Test
    fun negativeLengthsAndStrengths_clampToZero() {
        val spec = GlassAttrValues(
            refraction = -1f,
            dispersion = -2f,
            frostDp = -3f,
            saturation = -4f,
            contrast = -5f,
            cornerRadiusDp = -6f,
            bezelRadiusDp = -7f,
        ).toGlassSpec()

        assertEquals(0f, spec.refraction)
        assertEquals(0f, spec.dispersion)
        assertEquals(0f, spec.frostDp)
        assertEquals(0f, spec.saturation)
        assertEquals(0f, spec.contrast)
        assertEquals(0f, spec.cornerRadiusDp)
        assertEquals(0f, spec.bezel?.radiusDp)
    }

    @Test
    fun edgeLightAndBezelAlpha_clampToUnitRange() {
        val spec = GlassAttrValues(edgeLight = 3f, bezelAlpha = -1f).toGlassSpec()

        assertEquals(1f, spec.edgeLight)
        assertEquals(0f, spec.bezel?.alpha)
    }

    @Test
    fun bezel_isNullWhenNeitherAttributeIsSet() {
        assertNull(GlassAttrValues().toGlassSpec().bezel)
    }

    @Test
    fun bezelRadiusOnly_createsABezelWithTheDefaultAlpha() {
        val bezel = GlassAttrValues(bezelRadiusDp = 10f).toGlassSpec().bezel

        assertEquals(Bezel(radiusDp = 10f), bezel)
    }

    @Test
    fun partialBezelOverride_keepsTheBasesOtherFields() {
        val base = GlassSpec.AppleLight.copy(
            bezel = Bezel(radiusDp = 9f, offsetYDp = 3f, alpha = 0.2f),
        )

        val bezel = GlassAttrValues(bezelAlpha = 0.5f).toGlassSpec(base).bezel

        assertEquals(Bezel(radiusDp = 9f, offsetYDp = 3f, alpha = 0.5f), bezel)
    }

    @Test
    fun remainingScalarAttributes_mapThrough() {
        val spec = GlassAttrValues(
            tintArgb = 0x12345678,
            rimProfile = RimProfile.DROPLET,
            precisionMode = false,
            refraction = 1.5f,
            dispersion = 0.2f,
            frostDp = 12f,
            saturation = 1.2f,
            contrast = 0.9f,
            edgeLight = 0.4f,
            cornerRadiusDp = 20f,
        ).toGlassSpec()

        assertEquals(0x12345678, spec.tintArgb)
        assertEquals(RimProfile.DROPLET, spec.rimProfile)
        assertEquals(false, spec.precisionMode)
        assertEquals(1.5f, spec.refraction)
        assertEquals(0.2f, spec.dispersion)
        assertEquals(12f, spec.frostDp)
        assertEquals(1.2f, spec.saturation)
        assertEquals(0.9f, spec.contrast)
        assertEquals(0.4f, spec.edgeLight)
        assertEquals(20f, spec.cornerRadiusDp)
    }
}
