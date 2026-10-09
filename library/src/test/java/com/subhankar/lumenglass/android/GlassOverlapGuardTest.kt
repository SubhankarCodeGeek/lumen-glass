package com.subhankar.lumenglass.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the §4 overlap guard.
 *
 * The guard protects against the near-black-render failure mode (ARCHITECTURE §4), so it must not
 * itself be an untested blob. [checkOverlap] is pure float maths precisely so it can be covered
 * here, without a device.
 */
class GlassOverlapGuardTest {

    private val capture = OverlapRect(left = 0f, top = 0f, right = 100f, bottom = 100f)

    @Test
    fun fullyContainedSurface_overlaps() {
        val surface = OverlapRect(left = 10f, top = 10f, right = 90f, bottom = 90f)
        val verdict = checkOverlap(surface, capture)

        assertTrue(verdict.overlapping)
        assertEquals(1f, verdict.intersectionFraction, 1e-6f)
    }

    @Test
    fun identicalRects_overlap() {
        val verdict = checkOverlap(capture, capture)

        assertTrue(verdict.overlapping)
        assertEquals(1f, verdict.intersectionFraction, 1e-6f)
    }

    @Test
    fun partialOverlap_aboveFloor_overlaps() {
        // Surface spans x=50..150, capture x=0..100 -> half the surface is covered.
        val surface = OverlapRect(left = 50f, top = 0f, right = 150f, bottom = 100f)
        val verdict = checkOverlap(surface, capture)

        assertTrue(verdict.overlapping)
        assertEquals(0.5f, verdict.intersectionFraction, 1e-6f)
    }

    @Test
    fun disjointSurface_doesNotOverlap() {
        val surface = OverlapRect(left = 200f, top = 200f, right = 300f, bottom = 300f)
        val verdict = checkOverlap(surface, capture)

        assertFalse(verdict.overlapping)
        assertEquals(0f, verdict.intersectionFraction, 1e-6f)
    }

    @Test
    fun edgeTouchingRects_doNotOverlap() {
        // Surface's left edge exactly meets the capture's right edge: zero-area intersection.
        val surface = OverlapRect(left = 100f, top = 0f, right = 200f, bottom = 100f)
        val verdict = checkOverlap(surface, capture)

        assertFalse("Edge-touching is not an overlap (zero area)", verdict.overlapping)
        assertEquals(0f, verdict.intersectionFraction, 1e-6f)
    }

    @Test
    fun sliverOverlap_belowFloor_isReportedAsBroken() {
        // 0.5% of the surface overlaps: technically intersecting, visually broken.
        val surface = OverlapRect(left = 99.5f, top = 0f, right = 199.5f, bottom = 100f)
        val verdict = checkOverlap(surface, capture)

        assertFalse("A sub-floor sliver must be reported as broken", verdict.overlapping)
        assertTrue(verdict.intersectionFraction > 0f)
        assertTrue(verdict.intersectionFraction < MIN_OVERLAP_FRACTION)
    }

    @Test
    fun exactlyAtFloor_overlaps() {
        // Surface is 1000 wide; a capture strip covering exactly the first 1% of its width
        // intersects by exactly MIN_OVERLAP_FRACTION. The boundary is inclusive (>= floor passes).
        val surface = OverlapRect(left = 0f, top = 0f, right = 1000f, bottom = 100f)
        val coveredWidth = 1000f * MIN_OVERLAP_FRACTION // = 10f
        val stripCapture = OverlapRect(left = 0f, top = 0f, right = coveredWidth, bottom = 100f)

        val verdict = checkOverlap(surface, stripCapture)

        assertTrue("Exactly at the floor is acceptable", verdict.overlapping)
        assertEquals(MIN_OVERLAP_FRACTION, verdict.intersectionFraction, 1e-4f)
    }

    @Test
    fun emptySurfaceRect_isReportedAsBroken() {
        val surface = OverlapRect(left = 10f, top = 10f, right = 10f, bottom = 50f)
        val verdict = checkOverlap(surface, capture)

        assertFalse(verdict.overlapping)
        assertTrue(verdict.message.contains("no area"))
    }

    @Test
    fun emptyCaptureRect_isReportedAsBroken() {
        val emptyCapture = OverlapRect(left = 0f, top = 0f, right = 0f, bottom = 0f)
        val verdict = checkOverlap(capture, emptyCapture)

        assertFalse(verdict.overlapping)
        assertTrue(verdict.message.contains("nothing was recorded"))
    }

    @Test
    fun invertedSurfaceRect_isReportedAsBroken() {
        // right < left — a caller bug that would otherwise silently mis-render.
        val surface = OverlapRect(left = 100f, top = 10f, right = 10f, bottom = 90f)
        val verdict = checkOverlap(surface, capture)

        assertFalse(verdict.overlapping)
        assertTrue(verdict.message.contains("no area"))
    }

    @Test
    fun failureMessagesAreActionable() {
        val surface = OverlapRect(left = 200f, top = 200f, right = 300f, bottom = 300f)
        val verdict = checkOverlap(surface, capture)

        // The message must point at the cause, not just say "failed" — this is what a
        // developer sees in logcat at 2am.
        assertTrue(verdict.message.contains("near-black"))
        assertTrue(verdict.message.contains("ARCHITECTURE"))
    }
}
