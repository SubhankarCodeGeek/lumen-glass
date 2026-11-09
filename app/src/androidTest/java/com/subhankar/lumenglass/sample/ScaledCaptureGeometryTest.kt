/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.sample

import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subhankar.lumenglass.android.GlassCapture
import com.subhankar.lumenglass.android.GlassUniforms
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.LensModel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **Does a reduced-resolution capture fill its full surface region?**
 *
 * ## The question this settles
 *
 * `GlassCapture.record` allocates a *scaled-down* buffer but positions the node with the
 * **unscaled** surface region. Replaying that node must magnify the buffer to fill the region;
 * otherwise the content lands in the top-left buffer-sized area and the rest of the glass samples
 * empty space. A solid-colour test cannot see the difference — `GlassCaptureDiagnosticTest`'s
 * scaled case reads the buffer back directly, so it passes either way. This test draws an
 * **asymmetric** pattern and samples points that only contain the expected colour if the content
 * was magnified.
 *
 * ## How it renders
 *
 * A `RenderNode`'s content lives in a `RecordingCanvas`, and a `Bitmap`-backed canvas is not one —
 * `Canvas(bitmap).drawRenderNode(node)` silently does nothing. So the node is rendered on the GPU
 * through [HardwareRenderer] into an [ImageReader], exactly as `GlassCaptureDiagnosticTest` and
 * `ShaderMathParityTest` do. Instrumented-only for the same reason: there is no GPU in a JVM test.
 */
@RunWith(AndroidJUnit4::class)
class ScaledCaptureGeometryTest {

    private val sizePx = 128

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun assumeRenderNode() =
        assumeTrue("RenderNode requires API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

    private fun assumeAgs() =
        assumeTrue("AGSL requires API 33+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

    /**
     * Draws an asymmetric **surface-space** pattern: the left half red, the right half blue.
     *
     * Asymmetric on purpose. A uniform fill reads the same whether or not the reduced buffer was
     * magnified; the left/right split makes the difference visible at points away from the origin.
     */
    private fun drawSplit(canvas: Canvas, w: Int, h: Int) {
        val paint = Paint().apply { isAntiAlias = false }
        paint.color = red
        canvas.drawRect(0f, 0f, w / 2f, h.toFloat(), paint)
        paint.color = blue
        canvas.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    /**
     * Renders [node] on the GPU at [w]×[h] and returns the pixels as ARGB, row-major.
     *
     * Alpha is preserved (not forced opaque) so the "non-transparent" assertion in the effect test
     * fails if the sampled pixel really is empty.
     */
    private fun renderToPixels(node: RenderNode, w: Int, h: Int): IntArray {
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
            check(latch.await(5, TimeUnit.SECONDS)) {
                "render produced no frame-commit callback (GPU readback failure, not a capture bug)"
            }

            val image = reader.acquireLatestImage()
                ?: error("render produced no image (GPU readback failure, not a capture bug)")
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixels = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val o = y * plane.rowStride + x * plane.pixelStride
                        val r = buffer.get(o).toInt() and 0xFF
                        val g = buffer.get(o + 1).toInt() and 0xFF
                        val b = buffer.get(o + 2).toInt() and 0xFF
                        val a = buffer.get(o + 3).toInt() and 0xFF
                        pixels[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                return pixels
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    private fun IntArray.at(x: Int, y: Int, w: Int): Int = this[y * w + x]

    private fun hex(color: Int): String = "0x${color.toUInt().toString(16)}"

    /**
     * **The decisive geometry test.**
     *
     * Records the split pattern at `scale = 0.5f`, renders the capture's public node into a full
     * `w × h` target, then samples the full-size region.
     *
     * - Red at `w/8` proves the left half is still where surface coordinates put it.
     * - Blue at `3w/4` is the decisive one: at 0.5× the buffer is only `w/2` wide, so if the content
     *   were replayed unscaled (occupying the top-left buffer-sized area) this pixel would be
     *   transparent black, not blue.
     * - Blue at `7w/8` proves the right edge of the pattern reached the right edge of the region,
     *   not just that some blue was smeared near the middle.
     */
    @Test
    fun reducedCaptureMagnifiesContentToTheFullRegion() {
        assumeRenderNode()

        val w = sizePx
        val h = sizePx
        val capture = GlassCapture()
        try {
            capture.record(0f, 0f, w.toFloat(), h.toFloat(), scale = 0.5f) { canvas ->
                drawSplit(canvas, w, h)
            }

            val pixels = renderToPixels(capture.node, w, h)

            val left = pixels.at(w / 8, h / 2, w)
            val threeQuarters = pixels.at(3 * w / 4, h / 2, w)
            val sevenEighths = pixels.at(7 * w / 8, h / 2, w)

            assertTrue(
                "Expected red at (w/8, h/2) = (${w / 8}, ${h / 2}), got ${hex(left)}. The reduced " +
                    "buffer's left half should still read red after magnification.",
                left == red,
            )
            assertTrue(
                "Expected blue at (3w/4, h/2) = (${3 * w / 4}, ${h / 2}), got " +
                    "${hex(threeQuarters)}. THIS IS THE DECISIVE ASSERTION: the content was NOT " +
                    "magnified. A 0.5x capture replayed at the unscaled region leaves its content " +
                    "in the top-left buffer-sized area, so the right side samples empty space " +
                    "instead of blue.",
                threeQuarters == blue,
            )
            assertTrue(
                "Expected blue at (7w/8, h/2) = (${7 * w / 8}, ${h / 2}), got " +
                    "${hex(sevenEighths)}. The reduced buffer's content was not scaled to fill the " +
                    "full region.",
                sevenEighths == blue,
            )
        } finally {
            capture.release()
        }
    }

    /**
     * Control: at full scale the same pattern must sit at its surface coordinates.
     *
     * Without this, a bug that shifted the pattern for every scale could still pass the reduced
     * test for the wrong reason.
     */
    @Test
    fun fullScaleCaptureKeepsSurfaceCoordinates() {
        assumeRenderNode()

        val w = sizePx
        val h = sizePx
        val capture = GlassCapture()
        try {
            capture.record(0f, 0f, w.toFloat(), h.toFloat(), scale = 1f) { canvas ->
                drawSplit(canvas, w, h)
            }

            val pixels = renderToPixels(capture.node, w, h)
            val quarter = pixels.at(w / 4, h / 2, w)
            val threeQuarters = pixels.at(3 * w / 4, h / 2, w)

            assertTrue(
                "Expected red at (w/4, h/2), got ${hex(quarter)}. The full-scale control must keep " +
                    "the pattern at its surface coordinates.",
                quarter == red,
            )
            assertTrue(
                "Expected blue at (3w/4, h/2), got ${hex(threeQuarters)}. The full-scale control " +
                    "must keep the pattern at its surface coordinates.",
                threeQuarters == blue,
            )
        } finally {
            capture.release()
        }
    }

    /**
     * Smoke test for the effect path on a reduced capture.
     *
     * Every optical term is off — no lens, dispersion, frost, grading, tint or edge light — so the
     * shader must pass the buffer through unchanged. Recording at 0.5×, applying the effect and
     * sampling the full-region right side then proves two things at once:
     *
     * 1. the effect is attached to the node that holds the **buffer-resolution** content (a
     *    presentation-node effect would not see the content at buffer coordinates), and
     * 2. the surface-space uniforms were converted to buffer space, so the shader samples the
     *    region the content node actually covers.
     */
    @Test
    fun effectOnReducedCaptureAgreesWithBufferSpaceUniforms() {
        assumeRenderNode()
        assumeAgs()

        val w = sizePx
        val h = sizePx
        val capture = GlassCapture()
        try {
            capture.record(0f, 0f, w.toFloat(), h.toFloat(), scale = 0.5f) { canvas ->
                drawSplit(canvas, w, h)
            }

            val spec = GlassSpec(
                lens = LensModel.Centre(weight = 0f),
                refraction = 0f,
                dispersion = 0f,
                frostDp = 0f,
                tintArgb = 0,
                saturation = 1f,
                contrast = 1f,
                edgeLight = 0f,
            )
            val uniforms = GlassUniforms.from(
                spec = spec,
                widthPx = w.toFloat(),
                heightPx = h.toFloat(),
                cornerRadiusPx = 0f,
            )

            assertTrue(
                "applyGlass returned false on an AGSL-capable device; the effect could not be built.",
                capture.applyGlass(uniforms),
            )

            val pixels = renderToPixels(capture.node, w, h)
            val px = pixels.at(3 * w / 4, h / 2, w)
            val a = (px ushr 24) and 0xFF
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF

            assertTrue(
                "Expected a non-transparent blue-dominant pixel at (3w/4, h/2) after the effect, " +
                    "got ${hex(px)} (a=$a r=$r g=$g b=$b). A transparent or non-blue result means " +
                    "the effect node and the buffer-space uniforms do not agree: the lens is " +
                    "sampling a region the buffer-resolution content node does not cover.",
                a > 0 && b > r && b > g,
            )
        } finally {
            capture.release()
        }
    }
}
