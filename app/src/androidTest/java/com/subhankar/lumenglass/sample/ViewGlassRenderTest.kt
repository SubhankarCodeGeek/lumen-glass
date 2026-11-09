/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.sample

import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subhankar.lumenglass.core.Bezel
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.LensModel
import com.subhankar.lumenglass.core.QualityTier
import com.subhankar.lumenglass.view.LumenGlassSourceLayout
import com.subhankar.lumenglass.view.LumenGlassView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **Does the View backend actually render, and does it map XML to the shared material model?**
 *
 * ## Why pixels, not just state
 *
 * Every bug this backend can have looks identical from the state side: a bound source, a resolved
 * tier, a `glassSpec` that reads back correctly — and nothing on screen. A batching/`RenderNode`
 * mistake renders a transparent surface while every getter is right. So the rendering tests draw a
 * **known** source through the real view tree with [HardwareRenderer] and read the pixels back, and
 * the XML test asserts the *parsed* values rather than trusting that inflation worked.
 *
 * The rendering tests use a **passthrough** [GlassSpec] — no lens, refraction, dispersion, frost,
 * tint, grading or edge light — so the shader must return the sampled content unchanged. That makes
 * a colour assertion mean "the source was sampled at the right offset", not "some glass-looking
 * pixels appeared".
 *
 * ## Why `HardwareRenderer`
 *
 * A `Bitmap`-backed canvas is not a `RecordingCanvas`, so `Canvas(bitmap).drawRenderNode(node)`
 * silently does nothing — the source would be empty and the glass would take the frost path. The
 * only way to render a `RenderNode`/`RenderEffect` tree is the GPU, which is why these tests are
 * instrumented (`:app` is minSdk 33 for exactly this reason).
 */
@RunWith(AndroidJUnit4::class)
class ViewGlassRenderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assumeAgs() =
        assumeTrue("AGSL requires API 33+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

    /**
     * A material with every optical term off: the shader must pass the sampled content through.
     */
    private fun passthroughSpec(): GlassSpec = GlassSpec(
        lens = LensModel.Centre(weight = 0f),
        refraction = 0f,
        dispersion = 0f,
        frostDp = 0f,
        tintArgb = 0,
        saturation = 1f,
        contrast = 1f,
        edgeLight = 0f,
        bezel = null,
        cornerRadiusDp = 0f,
    )

    private fun blackRoot(): FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
    }

    private fun addView(
        parent: ViewGroup,
        child: View,
        width: Int,
        height: Int,
        left: Int = 0,
        top: Int = 0,
    ) {
        parent.addView(
            child,
            FrameLayout.LayoutParams(width, height).apply {
                leftMargin = left
                topMargin = top
            },
        )
    }

    /**
     * Renders [root] on the GPU at [w]×[h] and returns the pixels as ARGB, row-major.
     *
     * The root is measured and laid out here rather than by [HardwareRenderer]: a detached view
     * tree has no window to do it, and an unmeasured tree records nothing.
     *
     * `HardwareRenderer.setContentRoot` only accepts a `RenderNode`, so the view tree is recorded
     * into one first. That recording canvas is hardware-accelerated, which is what lets the glass
     * draw its own `RenderNode` and attach a `RenderEffect` — a `Bitmap` canvas would silently
     * record nothing.
     */
    private fun render(root: View, w: Int, h: Int): IntArray {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)

        val node = RenderNode("view-glass-test-content")
        val nodeCanvas = node.beginRecording(w, h)
        try {
            root.draw(nodeCanvas)
        } finally {
            node.endRecording()
        }
        // A RenderNode's position defaults to an empty rect, and HardwareRenderer draws it at that
        // position. Without this the entire frame is correctly recorded and then rendered nowhere.
        node.setPosition(0, 0, w, h)

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
                "render produced no frame-commit callback (GPU readback failure, not a glass bug)"
            }

            val image = reader.acquireLatestImage()
                ?: error("render produced no image (GPU readback failure, not a glass bug)")
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

    private fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    private fun red(color: Int): Int = (color ushr 16) and 0xFF
    private fun green(color: Int): Int = (color ushr 8) and 0xFF
    private fun blue(color: Int): Int = color and 0xFF

    private fun hex(color: Int): String = "0x${color.toUInt().toString(16)}"

    /**
     * The basic contract: a glass declared after a source samples that source, at the right offset.
     *
     * The glass is offset from the source, so a missing or wrong offset shows up as a wrong colour
     * (black, or a different part of the pattern), not as a subtly shifted blur.
     */
    @Test
    fun glassSamplesTheSource() {
        assumeAgs()

        val size = 200
        val root = blackRoot()

        val source = LumenGlassSourceLayout(context).apply {
            setBackgroundColor(Color.GREEN)
        }
        addView(root, source, size, size)

        val glass = LumenGlassView(context)
        addView(root, glass, 120, 120, left = 40, top = 40)
        glass.setQualityOverride(QualityTier.HIGH)
        glass.setGlassSpec(passthroughSpec())
        glass.setSource(source)

        val px = render(root, size, size).at(100, 100, size)

        assertTrue(
            "Expected the green source sampled through the glass at (100,100), got ${hex(px)} " +
                "(a=${alpha(px)} r=${red(px)} g=${green(px)} b=${blue(px)}). A transparent or " +
                "non-green pixel means the surface did not sample the source, or sampled it at " +
                "the wrong offset.",
            alpha(px) > 200 && green(px) > 150 && red(px) < 100 && blue(px) < 100,
        )
    }

    /**
     * One source feeds many surfaces, each with its own material and its own sampled region.
     *
     * Sampling two different quadrants proves each surface has its **own** capture and offset; a
     * shared capture or a shared offset would paint both with the same quadrant.
     */
    @Test
    fun multipleSurfacesSampleTheSameSource() {
        assumeAgs()

        val size = 200
        val root = blackRoot()

        val source = LumenGlassSourceLayout(context)
        addView(root, source, size, size)
        val colours = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
        for (index in colours.indices) {
            val quadrant = View(context).apply { setBackgroundColor(colours[index]) }
            addView(
                source,
                quadrant,
                width = size / 2,
                height = size / 2,
                left = (index % 2) * (size / 2),
                top = (index / 2) * (size / 2),
            )
        }

        val topLeftGlass = LumenGlassView(context)
        addView(root, topLeftGlass, 80, 80, left = 0, top = 0)
        topLeftGlass.setQualityOverride(QualityTier.HIGH)
        topLeftGlass.setGlassSpec(passthroughSpec())
        topLeftGlass.setSource(source)

        val bottomRightGlass = LumenGlassView(context)
        addView(root, bottomRightGlass, 80, 80, left = 120, top = 120)
        bottomRightGlass.setQualityOverride(QualityTier.HIGH)
        bottomRightGlass.setGlassSpec(passthroughSpec())
        bottomRightGlass.setSource(source)

        val pixels = render(root, size, size)

        val topLeft = pixels.at(40, 40, size)
        assertTrue(
            "Expected the top-left surface to sample the red quadrant, got ${hex(topLeft)}.",
            alpha(topLeft) > 200 && red(topLeft) > 150 && green(topLeft) < 100 && blue(topLeft) < 100,
        )

        val bottomRight = pixels.at(160, 160, size)
        assertTrue(
            "Expected the bottom-right surface to sample the white quadrant, got ${hex(bottomRight)}. " +
                "If this matches the top-left colour, the two surfaces share a capture or an offset.",
            alpha(bottomRight) > 200 && red(bottomRight) > 200 && green(bottomRight) > 200 &&
                blue(bottomRight) > 200,
        )
    }

    /**
     * With no source bound the surface must draw the shared FROST panel, not vanish.
     *
     * `0x47FFFFFF` over opaque black is `0x47474747` — an unmistakable grey. A missing fallback
     * would read as pure black, which is exactly the failure ARCHITECTURE §4 warns about.
     */
    @Test
    fun frostPanelIsDrawnWhenNoSourceIsBound() {
        val size = 200
        val root = blackRoot()

        val glass = LumenGlassView(context)
        addView(root, glass, 100, 100, left = 50, top = 50)
        glass.setGlassSpec(passthroughSpec())

        val px = render(root, size, size).at(100, 100, size)

        assertTrue(
            "Expected the frost fallback (~71,71,71) at (100,100), got ${hex(px)} " +
                "(r=${red(px)} g=${green(px)} b=${blue(px)}). Pure black means the surface drew " +
                "nothing when it had no source.",
            red(px) in 60..85 && green(px) in 60..85 && blue(px) in 60..85,
        )
    }

    /**
     * [LumenGlassView.release] is documented as idempotent and recoverable; both halves are asserted.
     *
     * Recoverable matters more than idempotent: a memory-pressure handler must be able to drop the
     * native capture without permanently blinding the surface.
     */
    @Test
    fun releaseIsIdempotentAndRecoverable() {
        assumeAgs()

        val size = 200
        val root = blackRoot()

        val source = LumenGlassSourceLayout(context).apply {
            setBackgroundColor(Color.GREEN)
        }
        addView(root, source, size, size)

        val glass = LumenGlassView(context)
        addView(root, glass, 120, 120, left = 40, top = 40)
        glass.setQualityOverride(QualityTier.HIGH)
        glass.setGlassSpec(passthroughSpec())
        glass.setSource(source)

        // Draw once so a capture and a cached effect actually exist to release.
        render(root, size, size)

        glass.release()
        glass.release()

        val px = render(root, size, size).at(100, 100, size)
        assertTrue(
            "Expected the surface to recover after release() and draw the green source again, " +
                "got ${hex(px)}. A frost-grey pixel means release() left the renderer permanently " +
                "unavailable.",
            alpha(px) > 200 && green(px) > 150 && red(px) < 100 && blue(px) < 100,
        )
    }

    /**
     * Children draw **above** the glass, so a surface can host its own labels and controls.
     *
     * The child is opaque and covers the whole surface; if the glass drew after its children the
     * red would be replaced by the sampled green.
     */
    @Test
    fun childrenDrawOnTopOfTheGlass() {
        assumeAgs()

        val size = 200
        val root = blackRoot()

        val source = LumenGlassSourceLayout(context).apply {
            setBackgroundColor(Color.GREEN)
        }
        addView(root, source, size, size)

        val glass = LumenGlassView(context)
        addView(root, glass, 120, 120, left = 40, top = 40)
        glass.setQualityOverride(QualityTier.HIGH)
        glass.setGlassSpec(passthroughSpec())
        glass.setSource(source)

        val overlay = View(context).apply { setBackgroundColor(Color.RED) }
        glass.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val px = render(root, size, size).at(100, 100, size)

        assertTrue(
            "Expected the red child drawn above the glass at (100,100), got ${hex(px)}. A green " +
                "pixel means the glass drew over its own children.",
            alpha(px) > 200 && red(px) > 150 && green(px) < 100 && blue(px) < 100,
        )
    }

    /**
     * XML attributes land on the shared [GlassSpec], through the same model the Compose API uses.
     *
     * This is the anti-divergence test: the View backend must not invent a second material model.
     * It also pins the two `null` contracts down — unset attributes keep [GlassSpec] defaults, and
     * an absent bezel stays absent.
     */
    @Test
    fun xmlAttributesMapToGlassSpec() {
        val root = LayoutInflater.from(context).inflate(R.layout.activity_view_glass, null)

        val source = root.findViewById<LumenGlassSourceLayout>(R.id.view_glass_source)
        val centre = root.findViewById<LumenGlassView>(R.id.view_glass_centre)
        val corner = root.findViewById<LumenGlassView>(R.id.view_glass_corner)

        assertNotNull("R.id.view_glass_source must inflate as a LumenGlassSourceLayout", source)
        assertNotNull("R.id.view_glass_centre must inflate as a LumenGlassView", centre)
        assertNotNull("R.id.view_glass_corner must inflate as a LumenGlassView", corner)

        val centreSpec = centre.glassSpec
        assertEquals("blend must map both weights", LensModel.Blend(0.6f, 0.4f), centreSpec.lens)
        assertEquals("frost must be converted dp -> GlassSpec dp", 8f, centreSpec.frostDp, 0.01f)
        assertEquals("corner radius must be in dp", 28f, centreSpec.cornerRadiusDp, 0.01f)
        assertEquals("tint must be ARGB with its own alpha", 0x33FFFFFF, centreSpec.tintArgb)
        assertEquals("bezel radius must be in dp", 10f, centreSpec.bezel?.radiusDp ?: -1f, 0.01f)
        assertEquals("bezel alpha", 0.12f, centreSpec.bezel?.alpha ?: -1f, 0.01f)
        assertEquals(
            "an unset bezel offset must keep the shared Bezel default",
            Bezel(radiusDp = 10f, alpha = 0.12f).offsetYDp,
            centreSpec.bezel?.offsetYDp ?: -1f,
            0.01f,
        )
        assertEquals(
            "lumenGlassQuality=balanced must resolve to BALANCED",
            QualityTier.BALANCED,
            centre.resolvedTier,
        )

        val cornerSpec = corner.glassSpec
        assertEquals("rim must map the rim weight only", LensModel.Rim(0.55f), cornerSpec.lens)
        assertEquals("dispersion", 0.06f, cornerSpec.dispersion, 0.001f)
        assertEquals("corner radius must be in dp", 66f, cornerSpec.cornerRadiusDp, 0.01f)
        assertEquals("tint must keep its alpha", 0x4D000000, cornerSpec.tintArgb)
        assertNull(
            "an absent bezel must stay null rather than being materialised from defaults",
            cornerSpec.bezel,
        )
        assertEquals(
            "lumenGlassQuality=high must resolve to HIGH",
            QualityTier.HIGH,
            corner.resolvedTier,
        )
    }

    /**
     * The `lumenGlassSource` reference resolves once the glass is attached to a window.
     *
     * Inflation alone cannot bind it: `LayoutInflater` calls `onFinishInflate` **before** adding
     * the glass to its parent, so `rootView` is still the glass itself. Attachment is the retry
     * point, and this test exists so that retry cannot regress silently.
     */
    @Test
    fun activityBindsXmlSourceOnAttach() {
        ActivityScenario.launch(ViewGlassActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val source = activity.findViewById<LumenGlassSourceLayout>(R.id.view_glass_source)
                val centre = activity.findViewById<LumenGlassView>(R.id.view_glass_centre)
                val corner = activity.findViewById<LumenGlassView>(R.id.view_glass_corner)

                assertTrue(
                    "The primary glass did not bind its XML source on attach; the surface would " +
                        "fall back to frost.",
                    centre.source === source,
                )
                assertTrue("The second glass did not bind the same source", corner.source === source)
            }
        }
    }
}
