/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.sample

import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **Diagnostic: does a `RenderNode` capture actually hold what we draw into it?**
 *
 * ## Why this test exists
 *
 * The Compose harness renders tint-over-black: the glass surface is present and the overlap guard
 * reports OK, but the shader samples an empty capture. That leaves two very different hypotheses,
 * which need different fixes:
 *
 * 1. **The capture pipeline is fine; the Compose content re-emission is not.** i.e. the
 *    `CanvasDrawScope` trick in `LumenSource` never draws into the capture.
 * 2. **The capture pipeline itself is broken.** i.e. `beginRecording`/`endRecording` plus node
 *    rendering does not round-trip here at all, in which case nothing downstream could ever work.
 *
 * This settles it by drawing a **known solid colour** into a `RenderNode` with no Compose involved
 * and reading the pixels back.
 *
 * ## How the readback works (and how it must NOT be done)
 *
 * A `RenderNode`'s content is drawn by a **`RecordingCanvas`**, and a `Bitmap`-backed `Canvas` is
 * *not* one — so the obvious `Canvas(bitmap).drawRenderNode(node)` silently does nothing (the cast
 * is null). Content is therefore read back the same way `ShaderMathParityTest` does it: render the
 * node on the GPU through `HardwareRenderer` into an `ImageReader` surface, then read the image.
 *
 * That is also why this must be an instrumented test: there is no GPU in a JVM test.
 */
@RunWith(AndroidJUnit4::class)
class GlassCaptureDiagnosticTest {

    private val sizePx = 64

    /** A colour nothing else in the harness uses, so it cannot be confused with a stale frame. */
    private val probeColor = 0xFF00FF00.toInt() // pure green

    private fun assumeRenderNode() =
        assumeTrue("RenderNode requires API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

    /**
     * Renders a node on the GPU and returns the pixel at its centre, as ARGB.
     */
    private fun readBackCentre(node: RenderNode, w: Int, h: Int): Int {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)

            val latch = CountDownLatch(1)
            renderer.createRenderRequest()
                .setWaitForPresent(true)
                .setFrameCommitCallback({ it.run() }, { latch.countDown() })
                .syncAndDraw()
            latch.await(5, TimeUnit.SECONDS)

            val image = reader.acquireLatestImage()
                ?: error("render produced no image (harness/GPU readback failure, not a capture bug)")
            try {
                val plane = image.planes[0]
                val offset = (h / 2) * plane.rowStride + (w / 2) * plane.pixelStride
                val b = plane.buffer
                val r = b.get(offset).toInt() and 0xFF
                val g = b.get(offset + 1).toInt() and 0xFF
                val bl = b.get(offset + 2).toInt() and 0xFF
                val a = b.get(offset + 3).toInt() and 0xFF
                return (a shl 24) or (r shl 16) or (g shl 8) or bl
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    @Test
    fun renderNodeHoldsWhatWeDrawIntoIt() {
        assumeRenderNode()

        val node = RenderNode("diagnostic")
        // Position MUST be set. A RenderNode with no position has zero bounds and renders nothing —
        // this was the first version of this test's bug, and it is the ordering GlassCapture uses
        // (record, endRecording, then setPosition).
        node.setPosition(0, 0, sizePx, sizePx)
        val canvas = node.beginRecording(sizePx, sizePx)
        try {
            canvas.drawColor(probeColor)
        } finally {
            node.endRecording()
        }

        val centre = readBackCentre(node, sizePx, sizePx)

        assertTrue(
            "Drawing a solid colour into a RenderNode produced 0x${centre.toUInt().toString(16)}, " +
                "expected 0xFF00FF00. If this fails the CAPTURE SUBSTRATE itself is broken — " +
                "nothing downstream (Compose modifiers, the shader) could work.",
            centre == probeColor,
        )
    }

    /**
     * The same check at the reduced scale `GlassCapture.record` uses.
     *
     * A scale transform on the recording canvas is easy to get wrong (drawing surface coordinates
     * into an already-scaled canvas lands off-buffer), so it is verified separately rather than
     * assumed from the test above.
     */
    @Test
    fun scaledRecordingStillHoldsContent() {
        assumeRenderNode()

        val node = RenderNode("diagnostic-scaled")
        val scale = 0.5f
        val scaled = (sizePx * scale).toInt()

        // Position first, as GlassCapture does.
        node.setPosition(0, 0, sizePx, sizePx)
        val canvas = node.beginRecording(scaled, scaled)
        try {
            // Mirrors GlassCapture.record: canvas scaled first, then the caller draws in surface
            // coordinates.
            canvas.scale(scale, scale)
            canvas.drawColor(probeColor)
        } finally {
            node.endRecording()
        }

        val centre = readBackCentre(node, scaled, scaled)

        assertTrue(
            "A scaled recording produced 0x${centre.toUInt().toString(16)}, expected 0xFF00FF00. " +
                "The reduced-capture path (captureScale) would silently capture nothing.",
            centre == probeColor,
        )
    }

    /**
     * Control: an empty node must read back transparent.
     *
     * Guards against the positive tests passing for the wrong reason — if an un-recorded node came
     * back green, those assertions would be meaningless.
     */
    @Test
    fun anEmptyNodeReadsBackEmpty() {
        assumeRenderNode()

        val node = RenderNode("diagnostic-empty")
        node.setPosition(0, 0, sizePx, sizePx)
        node.beginRecording(sizePx, sizePx)
        node.endRecording()

        val centre = readBackCentre(node, sizePx, sizePx)

        assertTrue(
            "An empty RenderNode read back 0x${centre.toUInt().toString(16)}; expected transparent. " +
                "A non-transparent value means the readback path is not what we think, and the " +
                "positive assertions cannot be trusted.",
            centre == 0,
        )
    }

    /**
     * **The decisive test for hypothesis 1 vs 2.**
     *
     * Records two different colours into the left and right halves of a node and verifies the
     * readback distinguishes them. A node that round-trips a *solid* fill could still fail to
     * capture *drawn content*; this asserts drawing actually lands, which is what the Compose
     * re-emission depends on.
     */
    @Test
    fun drawnShapesLandAtTheirCoordinates() {
        assumeRenderNode()

        val left = 0xFFFF0000.toInt()  // red
        val right = 0xFF0000FF.toInt() // blue
        val half = sizePx / 2

        val node = RenderNode("diagnostic-shapes")
        node.setPosition(0, 0, sizePx, sizePx)
        val canvas = node.beginRecording(sizePx, sizePx)
        try {
            canvas.drawColor(left)
            canvas.clipRect(half, 0, sizePx, sizePx)
            canvas.drawColor(right)
        } finally {
            node.endRecording()
        }

        val reader = ImageReader.newInstance(sizePx, sizePx, PixelFormat.RGBA_8888, 1)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            val latch = CountDownLatch(1)
            renderer.createRenderRequest()
                .setWaitForPresent(true)
                .setFrameCommitCallback({ it.run() }, { latch.countDown() })
                .syncAndDraw()
            latch.await(5, TimeUnit.SECONDS)

            val image = reader.acquireLatestImage() ?: error("no image")
            try {
                val p = image.planes[0]
                fun at(x: Int, y: Int): Int {
                    val o = y * p.rowStride + x * p.pixelStride
                    val b = p.buffer
                    return (0xFF shl 24) or
                        ((b.get(o).toInt() and 0xFF) shl 16) or
                        ((b.get(o + 1).toInt() and 0xFF) shl 8) or
                        (b.get(o + 2).toInt() and 0xFF)
                }
                val leftPx = at(sizePx / 4, sizePx / 2)
                val rightPx = at(sizePx * 3 / 4, sizePx / 2)

                assertTrue(
                    "Expected red on the left, got 0x${leftPx.toUInt().toString(16)}",
                    leftPx == left,
                )
                assertTrue(
                    "Expected blue on the right, got 0x${rightPx.toUInt().toString(16)}. " +
                        "If the left half is correct but this is not, clipping/drawing into the " +
                        "recording canvas does not behave as the capture path assumes.",
                    rightPx == right,
                )
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}
