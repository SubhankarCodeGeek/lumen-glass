package com.subhankar.lumenglass.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the tier → feature policy ([QualityTier.resolveFeatures]) and the probe ([selectTier]). */
class QualityTierTest {

    @Test
    fun high_enablesEverything() {
        val f = QualityTier.HIGH.resolveFeatures()
        assertTrue(f.useShader)
        assertTrue(f.useLens)
        assertTrue(f.useDispersion)
        assertTrue(f.useBezel)
        assertTrue(f.useBlur)
        assertEquals(1.0f, f.captureScale, 1e-6f)
    }

    @Test
    fun balanced_isCheaperThanHigh() {
        val f = QualityTier.BALANCED.resolveFeatures()
        assertTrue(f.useShader)
        assertFalse("dispersion is the most expensive effect; BALANCED leaves it off", f.useDispersion)
        assertFalse(f.useBezel)
        assertTrue(f.captureScale < 1.0f)
    }

    @Test
    fun low_dropsBlurAndReducesResolution() {
        val f = QualityTier.LOW.resolveFeatures()
        assertTrue(f.useShader)
        assertFalse("LOW runs no blur — tint only", f.useBlur)
        assertFalse(f.useDispersion)
        assertTrue(f.captureScale <= 0.5f)
    }

    @Test
    fun fallback_disablesTheShader() {
        val f = QualityTier.FALLBACK.resolveFeatures()
        assertFalse("FALLBACK must draw no shader", f.useShader)
        assertFalse(f.useLens)
        assertFalse(f.useBlur)
    }

    @Test
    fun tiers_areMonotonicInCost() {
        // capture scale must not increase as we go down the tiers (cheaper => smaller capture).
        val high = QualityTier.HIGH.resolveFeatures().captureScale
        val balanced = QualityTier.BALANCED.resolveFeatures().captureScale
        val low = QualityTier.LOW.resolveFeatures().captureScale
        assertTrue(high >= balanced)
        assertTrue(balanced >= low)
    }

    // ---- selectTier: the capability probe ----------------------------------

    @Test
    fun probe_selectsBalancedWhenAgsIsAvailable() {
        val tier = selectTier(apiLevel = AGSL_MIN_API_LEVEL)
        assertEquals(QualityTier.BALANCED, tier)
        assertTrue("the default must actually run the shader", tier.resolveFeatures().useShader)
    }

    @Test
    fun probe_selectsFallbackBelowAgs() {
        // One below the AGSL floor: the render path must degrade rather than attempt a shader.
        val tier = selectTier(apiLevel = AGSL_MIN_API_LEVEL - 1)
        assertEquals(QualityTier.FALLBACK, tier)
        assertFalse(tier.resolveFeatures().useShader)
    }

    @Test
    fun probe_neverReturnsLowOnItsOwn() {
        // LOW is a judgement about PERFORMANCE, not capability. A pure probe must not silently
        // downgrade to it — that would be exactly the invisible drop PLAN §5.2 forbids.
        for (api in listOf(21, 29, 31, 33, 34, 36, 40)) {
            val tier = selectTier(apiLevel = api)
            assertTrue(
                "probe returned $tier for api $api; it must only ever choose BALANCED or FALLBACK",
                tier == QualityTier.BALANCED || tier == QualityTier.FALLBACK,
            )
        }
    }

    @Test
    fun probe_overrideIsHonouredWhenTheDeviceCanRunIt() {
        // An app forcing LOW for battery, or HIGH for a demo, is obeyed on a capable device.
        assertEquals(QualityTier.LOW, selectTier(apiLevel = 36, requestedTier = QualityTier.LOW))
        assertEquals(QualityTier.HIGH, selectTier(apiLevel = 36, requestedTier = QualityTier.HIGH))
    }

    @Test
    fun probe_overrideIsClampedWhenTheDeviceCannotRunIt() {
        // A request for HIGH on a pre-AGSL device cannot be satisfied. Returning HIGH would produce
        // features claiming useShader = true for a surface that can only draw a frost panel — a lie
        // that is NOT detectable downstream, because the features would look correct.
        val actual = selectTier(apiLevel = 30, requestedTier = QualityTier.HIGH)
        assertEquals(QualityTier.FALLBACK, actual)
        assertFalse(
            "a clamped tier must describe a surface the device can actually draw",
            actual.resolveFeatures().useShader,
        )
    }

    @Test
    fun probe_clampingIsDetectableByTheCaller() {
        // The detectability contract: the caller can see the request was not honoured by comparing
        // the returned tier against the one it asked for.
        val requested = QualityTier.HIGH
        val actual = selectTier(apiLevel = 30, requestedTier = requested)
        assertTrue(
            "a clamp must be observable, not silent",
            actual != requested,
        )
    }

    @Test
    fun agslConstantMatchesTheAndroidLayerValue() {
        // `core/` mirrors the AGSL API level rather than importing `android/` (to stay
        // platform-agnostic). This asserts the mirror is not stale: `AGSL_MIN_API` in the
        // `android/` layer must equal `AGSL_MIN_API_LEVEL` here.
        assertEquals(
            "core's AGSL_MIN_API_LEVEL has drifted from android/'s AGSL_MIN_API",
            com.subhankar.lumenglass.android.AGSL_MIN_API,
            AGSL_MIN_API_LEVEL,
        )
    }
}
