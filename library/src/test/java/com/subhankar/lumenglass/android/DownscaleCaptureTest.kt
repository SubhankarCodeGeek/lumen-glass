package com.subhankar.lumenglass.android

import com.subhankar.lumenglass.core.resolveFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the reduced-resolution capture geometry.
 *
 * These matter because the scale maths sits on a boundary the overlap guard depends on: the
 * **buffer** shrinks while the **recorded region** stays in surface coordinates. Conflating the two
 * would silently corrupt the guard (ARCHITECTURE §4's near-black failure returning unnoticed), so
 * the arithmetic is pinned here rather than trusted inline.
 */
class DownscaleCaptureTest {

    @Test
    fun fullScale_bufferMatchesTheRegion() {
        val g = geometryFor(widthPx = 1080f, heightPx = 480f, scale = 1f)
        assertEquals(1080, g.bufferWidthPx)
        assertEquals(480, g.bufferHeightPx)
        assertEquals(1f, g.scale, 1e-6f)
    }

    @Test
    fun halfScale_quartersTheFragmentCount() {
        val g = geometryFor(widthPx = 1000f, heightPx = 400f, scale = 0.5f)
        assertEquals(500, g.bufferWidthPx)
        assertEquals(200, g.bufferHeightPx)
        // ~4x fewer fragments, which is the whole point of the lever.
        assertEquals(0.25f, (g.bufferWidthPx * g.bufferHeightPx) / (1000f * 400f), 1e-6f)
    }

    @Test
    fun scaleIsClampedToTheSupportedRange() {
        // Above 1 makes no sense (upscaling a capture wastes memory).
        assertEquals(1f, geometryFor(100f, 100f, scale = 5f).scale, 1e-6f)
        // Below the floor the glass stops reading as frosted and starts reading as broken.
        assertEquals(MIN_CAPTURE_SCALE, geometryFor(100f, 100f, scale = 0.01f).scale, 1e-6f)
        // A zero/negative scale must not produce a zero-sized buffer.
        assertTrue(geometryFor(100f, 100f, scale = 0f).bufferWidthPx >= 1)
        assertTrue(geometryFor(100f, 100f, scale = -3f).bufferHeightPx >= 1)
    }

    @Test
    fun bufferIsNeverZeroSized() {
        // `RenderNode.beginRecording` rejects a zero dimension, so a tiny surface at the minimum
        // scale must still yield a 1x1 buffer rather than crashing the render.
        val g = geometryFor(widthPx = 1f, heightPx = 1f, scale = MIN_CAPTURE_SCALE)
        assertTrue("width must be at least 1px", g.bufferWidthPx >= 1)
        assertTrue("height must be at least 1px", g.bufferHeightPx >= 1)
    }

    @Test
    fun inverseScaleRoundTrips() {
        // The shader needs this to map surface coordinates onto the smaller buffer.
        val g = geometryFor(800f, 600f, scale = 0.5f)
        assertEquals(2f, g.inverseScale, 1e-6f)
    }

    @Test
    fun everyTierScaleProducesALegalBuffer() {
        // Whatever `resolveFeatures()` hands us must be usable — including FALLBACK, which also
        // carries a capture scale even though it draws no shader.
        QualityTierScales.all.forEach { scale ->
            val g = geometryFor(widthPx = 1080f, heightPx = 480f, scale = scale)
            assertTrue("scale $scale produced a zero-width buffer", g.bufferWidthPx >= 1)
            assertTrue("scale $scale produced a zero-height buffer", g.bufferHeightPx >= 1)
            assertTrue("scale $scale was clamped unexpectedly", g.scale <= 1f)
            assertTrue("scale $scale fell below the floor", g.scale >= MIN_CAPTURE_SCALE)
        }
    }
}

/** The capture scales the tier policy can produce, so the test tracks the real policy. */
private object QualityTierScales {
    val all: List<Float> = com.subhankar.lumenglass.core.QualityTier.entries
        .map { it.resolveFeatures().captureScale }
}
