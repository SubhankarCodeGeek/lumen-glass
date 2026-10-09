package com.subhankar.lumenglass.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the tier → feature policy ([QualityTier.resolveFeatures]). */
class QualityTierTest {

    @Test
    fun high_enablesEverything() {
        val f = QualityTier.HIGH.resolveFeatures()
        assertTrue(f.useShader)
        assertTrue(f.useLens)
        assertTrue(f.useDispersion)
        assertTrue(f.useBezel)
        assertTrue(f.useSeparableBlur)
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
    fun low_usesSinglePassAndReducedResolution() {
        val f = QualityTier.LOW.resolveFeatures()
        assertTrue(f.useShader)
        assertFalse(f.useSeparableBlur)
        assertFalse(f.useDispersion)
        assertTrue(f.captureScale <= 0.5f)
    }

    @Test
    fun fallback_disablesTheShader() {
        val f = QualityTier.FALLBACK.resolveFeatures()
        assertFalse("FALLBACK must draw no shader", f.useShader)
        assertFalse(f.useLens)
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
}
