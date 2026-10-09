package com.subhankar.lumenglass.sample

import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.media.ImageReader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subhankar.lumenglass.android.LumenGlassTestApi
import com.subhankar.lumenglass.core.GlassMath
import com.subhankar.lumenglass.core.LensModel
import com.subhankar.lumenglass.core.resolveWeights
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * **Q-F: numeric parity between the shader and `core/GlassMath`.**
 *
 * ## What this proves
 *
 * `GlassMath` is the *specification* for the glass maths and the AGSL shader is meant to implement
 * it. Before this test nothing checked that — and the two had silently drifted apart (different
 * lens scale terms; fundamentally different dispersion formulations). `GlassMath`'s KDoc claimed
 * they mirrored each other. It was false.
 *
 * This renders a diagnostic probe that outputs the **displacement vector itself**, reads the pixels
 * back, and compares against `GlassMath.lensOffset`.
 *
 * ## Why a probe rather than a pixel-diff of the glass
 *
 * Diffing a composited glass surface would mostly measure the *harness*: colour management,
 * premultiplied alpha, tint, grading, driver variance. The probe isolates the one thing the
 * contract is about — the maths. (Dispersion is covered structurally, see
 * `dispersionScalesAreImplementedByTheProductionShader`, because the probe renders displacement,
 * not colour.)
 *
 * ## Why this is not just "two copies of the maths agreeing"
 *
 * The probe and the production shader are generated from the **same expression text**
 * (`LumenGlassTestApi.ProbeExpressions`), so there is one copy of each term. A change flows into both, and a
 * disagreement with `GlassMath` fails here. The shared wiring is itself asserted (see
 * `probeAndProductionShaderShareTheLensExpression`) so nobody can "fix" a failure by editing the
 * probe alone.
 *
 * ## Rendering mechanism (why it is not a plain Bitmap canvas)
 *
 * `RuntimeShader` is GPU-only. Drawing into a software `Canvas` backed by a `Bitmap` throws
 * `IllegalArgumentException: Software rendering doesn't support RuntimeShader` — the first version
 * of this test failed exactly that way. So the probe is rendered on the GPU via
 * [HardwareRenderer] into an [ImageReader]'s `Surface`, and the `Image` is read back.
 *
 * Instrumented-only for the same reason: there is no GPU in a JVM test.
 */
@RunWith(AndroidJUnit4::class)
class ShaderMathParityTest {

    /**
     * Surface under test. Deliberately **non-square** — the case where the lens scale term
     * (`min(halfWidth, halfHeight)`) had a real chance to drift, making it the most valuable shape
     * to test.
     */
    private val widthPx = 128
    private val heightPx = 64

    /**
     * Tolerance in pixels.
     *
     * The probe encodes through an 8-bit channel: one step is `2 * encodeRange / 255` ≈
     * 2.0 px, so half a step is the best achievable discrimination. The tolerance allows a little
     * more for `half4` rounding. It is **derived from the encoding**, not picked to make the test
     * green — a tolerance chosen loosely enough to always pass would make this decorative.
     */
    private val tolerancePx = 3.0f

    private fun assumeAgs() =
        assumeTrue("AGSL requires API 33+", Build.VERSION.SDK_INT >= LumenGlassTestApi.agslMinApi)

    /**
     * Renders the probe on the GPU and reads back the displacement at one pixel.
     *
     * @return displacement `(dx, dy)` in pixels, decoded from the red/green channels.
     */
    private fun probeDisplacement(
        model: LensModel,
        refraction: Float,
        px: Int,
        py: Int,
    ): FloatArray {
        val weights = model.resolveWeights()
        val shader = RuntimeShader(LumenGlassTestApi.probeShaderSource(LumenGlassTestApi.probeEncodeRange)).apply {
            setFloatUniform("size", widthPx.toFloat(), heightPx.toFloat())
            setFloatUniform("cornerRadius", 8f)
            setFloatUniform("centreWeight", weights.centre)
            setFloatUniform("rimWeight", weights.rim)
            setFloatUniform("squircleExp", 8f)
            setFloatUniform("refraction", refraction)
        }

        val reader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 1)
        val renderer = HardwareRenderer()
        try {
            val node = RenderNode("probe").apply {
                setPosition(0, 0, widthPx, heightPx)
                val canvas = beginRecording(widthPx, heightPx)
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }.let { paint ->
                    canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)
                }
                endRecording()
            }

            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)

            // The frame must actually be COMMITTED to the surface before ImageReader can hand us
            // an image — `syncAndDraw()` alone returns before the buffer is produced, which is why
            // the first version of this test saw "no image". `setFrameCommitCallback` fires once
            // the frame has reached the consumer, so we block on it rather than guessing.
            val latch = java.util.concurrent.CountDownLatch(1)
            renderer.createRenderRequest()
                .setWaitForPresent(true)
                .setFrameCommitCallback({ it.run() }, { latch.countDown() })
                .syncAndDraw()

            val committed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)

            val image = reader.acquireLatestImage()
            if (image == null) {
                // Distinguish "the render never landed" (a harness problem) from "the maths is
                // wrong" (the thing this test is about). Failing with the former would be
                // misleading, so the message says which it is.
                error(
                    "probe render produced no image (frameCommitted=$committed). " +
                        "This is a harness/GPU-readback failure, NOT a maths mismatch.",
                )
            }
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val offset = py * rowStride + px * pixelStride
                val buffer = plane.buffer
                // RGBA_8888: R at +0, G at +1.
                val r = (buffer.get(offset).toInt() and 0xFF) / 255f
                val g = (buffer.get(offset + 1).toInt() and 0xFF) / 255f
                return floatArrayOf(
                    LumenGlassTestApi.decodeProbeValue(r),
                    LumenGlassTestApi.decodeProbeValue(g),
                )
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    /**
     * The reference value, computed by the *specification*.
     *
     * Reproduces the shader's own coordinate pipeline (normalize -> squircle -> weight) so any
     * disagreement is about the **maths**, not about setup.
     */
    private fun expectedDisplacement(
        model: LensModel,
        refraction: Float,
        px: Int,
        py: Int,
    ): FloatArray {
        val weights = model.resolveWeights()
        val halfWidth = widthPx / 2f
        val halfHeight = heightPx / 2f

        val nx = (px - halfWidth) / halfWidth
        val ny = (py - halfHeight) / halfHeight
        val sq = GlassMath.squircle(nx, ny, exponent = 8f)

        return GlassMath.lensOffset(
            nx = nx,
            ny = ny,
            squircleValue = sq,
            centreWeight = weights.centre,
            rimWeight = weights.rim,
            refraction = refraction,
            halfWidth = halfWidth,
            halfHeight = halfHeight,
        )
    }

    /**
     * The core assertion across a grid of sample points for one lens model.
     *
     * Points cover centre, mid-surface, a diagonal, and near-rim. A lens disagreement is usually
     * strongest at the extremes, so testing only the middle would hide it.
     */
    private fun assertParityFor(
        model: LensModel,
        refraction: Float = 1.0f,
    ) {
        val samples = listOf(
            widthPx / 2 to heightPx / 2,          // exact centre
            widthPx / 4 to heightPx / 2,          // mid-surface, horizontal
            widthPx / 2 to heightPx / 4,          // mid-surface, vertical
            widthPx / 4 to heightPx / 4,          // diagonal interior
            widthPx - 4 to heightPx / 2,          // near right rim
            widthPx / 2 to heightPx - 4,          // near bottom rim
        )

        samples.forEach { (px, py) ->
            val actual = probeDisplacement(model, refraction, px, py)
            val expected = expectedDisplacement(model, refraction, px, py)

            assertTrue(
                "dx mismatch at ($px,$py) for $model: shader=${actual[0]} " +
                    "GlassMath=${expected[0]} (tol=$tolerancePx)",
                abs(actual[0] - expected[0]) <= tolerancePx,
            )
            assertTrue(
                "dy mismatch at ($px,$py) for $model: shader=${actual[1]} " +
                    "GlassMath=${expected[1]} (tol=$tolerancePx)",
                abs(actual[1] - expected[1]) <= tolerancePx,
            )
        }
    }

    // ---- the three lens models ---------------------------------------------

    @Test
    fun centreLensMatchesGlassMath() {
        assumeAgs()
        assertParityFor(LensModel.Centre(weight = 0.9f))
    }

    @Test
    fun rimLensMatchesGlassMath() {
        assumeAgs()
        assertParityFor(LensModel.Rim(weight = 0.9f))
    }

    @Test
    fun blendLensMatchesGlassMath() {
        assumeAgs()
        assertParityFor(LensModel.Blend(centre = 0.6f, rim = 0.4f))
    }

    // ---- the behaviours the models are defined by --------------------------

    @Test
    fun rimLensIsZeroAtTheCentre() {
        assumeAgs()
        // The defining property of the incumbent (rim-only) model: no displacement in the middle.
        val actual = probeDisplacement(
            LensModel.Rim(weight = 1f), refraction = 1f, px = widthPx / 2, py = heightPx / 2,
        )
        assertTrue(
            "rim-only lens must not displace the exact centre, but moved (${actual[0]}, ${actual[1]})",
            abs(actual[0]) <= tolerancePx && abs(actual[1]) <= tolerancePx,
        )
    }

    @Test
    fun centreLensDoesDisplaceTheMidSurface() {
        assumeAgs()
        // The core differentiator: a centre-weighted lens bends MID-surface content, which a
        // rim-only lens leaves alone. Asserting it guards the library's headline claim.
        val actual = probeDisplacement(
            LensModel.Centre(weight = 1f), refraction = 1f, px = widthPx / 4, py = heightPx / 2,
        )
        assertTrue(
            "centre-weighted lens should displace a mid-surface pixel, but dx=${actual[0]}",
            abs(actual[0]) > tolerancePx,
        )
    }

    @Test
    fun refractionZeroProducesNoDisplacement() {
        assumeAgs()
        val actual = probeDisplacement(
            LensModel.Blend(centre = 0.6f, rim = 0.4f), refraction = 0f,
            px = widthPx / 4, py = heightPx / 2,
        )
        assertTrue(
            "refraction = 0 must produce zero displacement, got (${actual[0]}, ${actual[1]})",
            abs(actual[0]) <= tolerancePx && abs(actual[1]) <= tolerancePx,
        )
    }

    // ---- structural guards (no GPU needed) ---------------------------------

    /**
     * **Structural guard:** the probe and the production shader must share their maths text.
     *
     * Without this, someone could "fix" a parity failure by editing the probe alone, leaving the
     * production shader wrong while the test stays green — the exact duplicate-copy failure mode
     * this design exists to prevent.
     */
    @Test
    fun probeAndProductionShaderShareTheLensExpression() {
        assertTrue(
            "LUMEN_GLASS_AGSL must interpolate the shared lens-weight expression; if it " +
                "hard-codes its own, the parity test proves nothing",
            LumenGlassTestApi.shaderSource.contains(LumenGlassTestApi.ProbeExpressions.lensWeight),
        )
        assertTrue(
            "LUMEN_GLASS_AGSL must interpolate the shared lens-scale expression",
            LumenGlassTestApi.shaderSource.contains(LumenGlassTestApi.ProbeExpressions.lensScale),
        )
        assertTrue(
            "LUMEN_GLASS_AGSL must interpolate the shared squircle expression",
            LumenGlassTestApi.shaderSource.contains(LumenGlassTestApi.ProbeExpressions.squircle),
        )
    }

    /**
     * **Structural guard for dispersion.** Q-H's fix replaced the shader's offset formulation with
     * `GlassMath.dispersionScales`' radial scaling. The probe renders displacement, not colour, so
     * dispersion is checked here rather than numerically: the production shader must derive its
     * channel scales from `1.0 +/- dispersion` and must **not** carry the old offset constant.
     */
    @Test
    fun dispersionScalesAreImplementedByTheProductionShader() {
        assertTrue(
            "production shader must scale red by (1 + dispersion) — the GlassMath formulation (Q-H)",
            LumenGlassTestApi.shaderSource.contains("1.0 + dispersion"),
        )
        assertTrue(
            "production shader must scale blue by (1 - dispersion) — the GlassMath formulation (Q-H)",
            LumenGlassTestApi.shaderSource.contains("1.0 - dispersion"),
        )
        assertTrue(
            "the old linear-offset dispersion (constant 0.05) must not reappear; it disagreed " +
                "with GlassMath and was removed in Q-H",
            !LumenGlassTestApi.shaderSource.contains("0.05"),
        )
    }
}
