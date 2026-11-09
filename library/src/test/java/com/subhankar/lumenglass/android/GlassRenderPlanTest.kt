/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.android

import com.subhankar.lumenglass.core.Bezel
import com.subhankar.lumenglass.core.GlassFeatures
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.QualityTier
import com.subhankar.lumenglass.core.RimProfile
import com.subhankar.lumenglass.core.resolveFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the single render policy ([resolveGlassRenderPlan]).
 *
 * The point of this class is the **pixel-free parity gate**: the policy used to live inline in
 * `LumenGlassNode.draw`, and [oldInlinePlan] reproduces that formula verbatim. Every spec / tier /
 * density combination the Compose path can hit is asserted to produce exactly the same uniforms
 * and frost radius as the old code, so extracting the function cannot change the material.
 *
 * No Robolectric and no Android runtime: [GlassUniforms.from], the tier resolution and the plan are
 * all pure value maths.
 */
class GlassRenderPlanTest {

    private val widthPx = 480f
    private val heightPx = 320f

    /**
     * The OLD inline feature-to-uniform policy from `LumenGlassNode.draw`, kept as the parity
     * oracle. Note it keys the bezel radius off `useDispersion`; for every resolvable
     * [QualityTier], `useDispersion` and `useBezel` agree (both on for HIGH, both off otherwise),
     * which is why the new `useBezel` gating is bit-for-bit compatible.
     */
    private fun oldInlinePlan(
        spec: GlassSpec,
        density: Float,
        features: GlassFeatures,
    ): GlassRenderPlan {
        val uniforms = GlassUniforms.from(
            spec = spec,
            widthPx = widthPx,
            heightPx = heightPx,
            cornerRadiusPx = spec.cornerRadiusDp * density,
            dispersion = if (features.useDispersion) spec.dispersion else 0f,
            refraction = spec.refraction,
            tier = if (features.useDispersion) TIER_HIGH else TIER_BALANCED,
            bezelRadiusPx = if (features.useDispersion) (spec.bezel?.radiusDp ?: 0f) * density else 0f,
        )
        return GlassRenderPlan(
            uniforms = uniforms,
            frostRadiusPx = if (features.useBlur) spec.frostDp * density else 0f,
        )
    }

    /** The spec matrix: defaults, Apple-calibrated, edge cases, and every [RimProfile]. */
    private fun specs(): List<GlassSpec> = listOf(
        GlassSpec.Default,
        GlassSpec.AppleLight,
        GlassSpec(dispersion = 0f),
        GlassSpec(bezel = Bezel(radiusDp = 10f, offsetYDp = 7f, alpha = 0.2f)),
        GlassSpec(cornerRadiusDp = 16f, frostDp = 9f),
        GlassSpec(precisionMode = false),
    ) + RimProfile.entries.map { GlassSpec(rimProfile = it) }

    // ---- The parity gate ---------------------------------------------------

    @Test
    fun plan_matchesTheOldInlineFormula_fieldForField() {
        for (tier in QualityTier.entries) {
            val features = tier.resolveFeatures()
            for (spec in specs()) {
                for (density in listOf(1f, 2f, 3f)) {
                    val expected = oldInlinePlan(spec, density, features)
                    val actual = resolveGlassRenderPlan(spec, widthPx, heightPx, density, features)
                    val message = "tier=$tier density=$density spec=$spec"

                    assertEquals("$message frostRadiusPx", expected.frostRadiusPx, actual.frostRadiusPx, 1e-6f)
                    assertUniformsEqual(message, expected.uniforms, actual.uniforms)
                }
            }
        }
    }

    // ---- Explicit policy assertions ---------------------------------------

    @Test
    fun dispersion_isZeroedWhenTheTierDisablesIt() {
        val spec = GlassSpec(dispersion = 0.42f)
        for (tier in QualityTier.entries) {
            val features = tier.resolveFeatures()
            val plan = resolveGlassRenderPlan(spec, widthPx, heightPx, density = 3f, features = features)

            if (features.useDispersion) {
                assertEquals("$tier keeps the requested dispersion", 0.42f, plan.uniforms.dispersion, 1e-6f)
            } else {
                assertEquals("$tier must zero dispersion", 0f, plan.uniforms.dispersion, 0f)
            }
        }
    }

    @Test
    fun tierUniformIsHighExactlyWhenDispersionIsEnabled() {
        for (tier in QualityTier.entries) {
            val features = tier.resolveFeatures()
            val plan = resolveGlassRenderPlan(GlassSpec.Default, widthPx, heightPx, 2f, features)
            val expected = if (features.useDispersion) TIER_HIGH else TIER_BALANCED

            assertEquals("$tier tier uniform", expected, plan.uniforms.tier, 0f)
        }

        // The matrix above must actually exercise both branches, or "exactly when" is vacuous.
        assertTrue("HIGH must enable dispersion", QualityTier.HIGH.resolveFeatures().useDispersion)
        assertFalse("BALANCED must not enable dispersion", QualityTier.BALANCED.resolveFeatures().useDispersion)
    }

    @Test
    fun bezelRadiusPxIsZeroWhenTheTierDisablesTheBezel() {
        val spec = GlassSpec(bezel = Bezel(radiusDp = 12f, alpha = 0.3f))
        for (tier in QualityTier.entries) {
            val features = tier.resolveFeatures()
            val plan = resolveGlassRenderPlan(spec, widthPx, heightPx, density = 3f, features = features)

            if (features.useBezel) {
                assertEquals("$tier bezelRadiusPx", 12f * 3f, plan.uniforms.bezelRadiusPx, 1e-6f)
            } else {
                assertEquals("$tier must drop the bezel radius", 0f, plan.uniforms.bezelRadiusPx, 0f)
            }
        }
    }

    @Test
    fun bezelAlphaStaysZeroBelowHighEvenWhenTheSpecAsksForIt() {
        val spec = GlassSpec(bezel = Bezel(radiusDp = 12f, alpha = 0.5f))
        for (tier in QualityTier.entries) {
            val features = tier.resolveFeatures()
            val plan = resolveGlassRenderPlan(spec, widthPx, heightPx, 2f, features)
            val expected = if (tier == QualityTier.HIGH) 0.5f else 0f

            assertEquals("$tier bezelAlpha", expected, plan.uniforms.bezelAlpha, 1e-6f)
        }
    }

    @Test
    fun frostRadiusPxIsZeroWhenTheTierDisablesBlur() {
        val spec = GlassSpec(frostDp = 9f)
        for (tier in QualityTier.entries) {
            val features = tier.resolveFeatures()
            for (density in listOf(2f, 3f)) {
                val plan = resolveGlassRenderPlan(spec, widthPx, heightPx, density, features)

                if (features.useBlur) {
                    assertEquals("$tier density=$density frost", 9f * density, plan.frostRadiusPx, 1e-6f)
                } else {
                    assertEquals("$tier density=$density must drop frost", 0f, plan.frostRadiusPx, 0f)
                }
            }
        }
    }

    @Test
    fun dpFieldsAreScaledByDensityExactlyOnce() {
        // The spec stores dp (the DSL builds with density 1f), so the plan must multiply by the real
        // density once — 16 dp at density 3 is 48 px, never 144 px.
        val spec = GlassSpec(
            cornerRadiusDp = 16f,
            frostDp = 9f,
            bezel = Bezel(radiusDp = 12f, alpha = 0.2f),
        )
        val high = QualityTier.HIGH.resolveFeatures()

        for (density in listOf(2f, 3f)) {
            val plan = resolveGlassRenderPlan(spec, widthPx, heightPx, density, high)

            assertEquals("cornerRadiusPx at density=$density", 16f * density, plan.uniforms.cornerRadiusPx, 1e-6f)
            assertEquals("bezelRadiusPx at density=$density", 12f * density, plan.uniforms.bezelRadiusPx, 1e-6f)
            assertEquals("frostRadiusPx at density=$density", 9f * density, plan.frostRadiusPx, 1e-6f)
            // Pixel dimensions are already px and must pass through untouched.
            assertEquals(widthPx, plan.uniforms.widthPx, 0f)
            assertEquals(heightPx, plan.uniforms.heightPx, 0f)
        }
    }

    @Test
    fun bezelOffsetYIsNeverPropagatedToAnyUniform() {
        // GlassUniforms has no offset slot; this pins that a spec's offsetY cannot sneak into some
        // other field as the policy grows.
        val specA = GlassSpec(bezel = Bezel(radiusDp = 12f, offsetYDp = 0f, alpha = 0.2f))
        val specB = GlassSpec(bezel = Bezel(radiusDp = 12f, offsetYDp = 99f, alpha = 0.2f))

        for (tier in QualityTier.entries) {
            val features = tier.resolveFeatures()
            val a = resolveGlassRenderPlan(specA, widthPx, heightPx, 3f, features)
            val b = resolveGlassRenderPlan(specB, widthPx, heightPx, 3f, features)

            assertUniformsEqual("$tier must ignore Bezel.offsetYDp", a.uniforms, b.uniforms)
            assertEquals("$tier frost must ignore Bezel.offsetYDp", a.frostRadiusPx, b.frostRadiusPx, 0f)
        }
    }

    // ---- Helpers -----------------------------------------------------------

    /** Compares every field by name, so a failure points at the uniform that drifted. */
    private fun assertUniformsEqual(
        message: String,
        expected: GlassUniforms,
        actual: GlassUniforms,
    ) {
        assertEquals("$message widthPx", expected.widthPx, actual.widthPx, 1e-6f)
        assertEquals("$message heightPx", expected.heightPx, actual.heightPx, 1e-6f)
        assertEquals("$message cornerRadiusPx", expected.cornerRadiusPx, actual.cornerRadiusPx, 1e-6f)
        assertEquals("$message centreWeight", expected.centreWeight, actual.centreWeight, 1e-6f)
        assertEquals("$message rimWeight", expected.rimWeight, actual.rimWeight, 1e-6f)
        assertEquals("$message squircleExp", expected.squircleExp, actual.squircleExp, 1e-6f)
        assertEquals("$message refraction", expected.refraction, actual.refraction, 1e-6f)
        assertEquals("$message dispersion", expected.dispersion, actual.dispersion, 1e-6f)
        assertEquals("$message tintArgb", expected.tintArgb, actual.tintArgb)
        assertEquals("$message saturation", expected.saturation, actual.saturation, 1e-6f)
        assertEquals("$message contrast", expected.contrast, actual.contrast, 1e-6f)
        assertEquals("$message edgeLight", expected.edgeLight, actual.edgeLight, 1e-6f)
        assertEquals("$message tier", expected.tier, actual.tier, 1e-6f)
        assertEquals("$message bezelAlpha", expected.bezelAlpha, actual.bezelAlpha, 1e-6f)
        assertEquals("$message bezelRadiusPx", expected.bezelRadiusPx, actual.bezelRadiusPx, 1e-6f)
        assertEquals("$message precisionMode", expected.precisionMode, actual.precisionMode, 1e-6f)
        assertEquals("$message rimProfile", expected.rimProfile, actual.rimProfile, 1e-6f)
    }
}
