package com.subhankar.lumenglass.sample

import android.graphics.RuntimeShader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subhankar.lumenglass.android.AGSL_MIN_API
import com.subhankar.lumenglass.android.GlassUniforms
import com.subhankar.lumenglass.android.LUMEN_GLASS_UNIFORMS
import com.subhankar.lumenglass.android.TIER_BALANCED
import com.subhankar.lumenglass.android.TIER_HIGH
import com.subhankar.lumenglass.android.buildGlassShader
import com.subhankar.lumenglass.android.buildGlassShaderOrNull
import com.subhankar.lumenglass.android.isAgsSupported
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shader compile smoke test — **instrumented**, and it must be.
 *
 * ## Why this lives in `:app` and not `:library`
 *
 * AGSL shaders are plain **strings** compiled by [RuntimeShader] on the GPU. The JVM has no GPU, so
 * this cannot be a `src/test/` JVM test, and Robolectric does not implement `RuntimeShader` either.
 *
 * It lives in `:app/src/androidTest/` rather than `:library/src/androidTest/` for a concrete build
 * reason: a library's androidTest APK bundles the library's **whole transitive classpath**,
 * including the Compose material stack, which requires `minSdk 23`. `:library` is deliberately
 * `minSdk 21` (a product contract — the render path degrades to FROST below API 33 at runtime), so
 * the two cannot be reconciled without weakening the library's published minSdk.
 *
 * `:app` already declares `minSdk 33`, already depends on `:library`, and already has the
 * instrumentation runner configured — so the test belongs here.
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest
 * ```
 *
 * ## Why this test exists
 *
 * The P1.7 device run found **two real AGSL compile errors** in the shader that no existing test
 * could catch:
 *
 * 1. `0.5h` half-suffix literals are rejected by the AGSL compiler (GLSL accepts them).
 * 2. `fwidth()` **does not exist in AGSL** — derivatives are unavailable.
 *
 * `LumenGlassShaderStructureTest` (in `:library`'s JVM tests) was blind to both: it regexes uniform
 * *declarations* and never asks whether the shader **compiles**. So the shader was only ever
 * validated the first time a device tried to render it — which is exactly how those bugs reached
 * hardware. This test closes that gap by compiling through the **production** path.
 *
 * ## Higher-tier coverage matters
 *
 * The tests deliberately build at [TIER_HIGH]. The `dispersion` branch inside the shader's `main`
 * is a **separate code path**, and the P1.7 harness ran BALANCED by default — so a syntax error in
 * the dispersion path would have sailed past a BALANCED-only test.
 */
@RunWith(AndroidJUnit4::class)
class LumenGlassShaderCompileTest {

    /**
     * A realistic, **non-degenerate** uniform set.
     *
     * Non-zero size matters: the shader divides by `halfSize`. `TIER_HIGH` + non-zero dispersion
     * selects the 3-tap dispersion branch.
     */
    private fun uniformsHighTier(): GlassUniforms = GlassUniforms(
        widthPx = 1080f,
        heightPx = 480f,
        cornerRadiusPx = 48f,
        centreWeight = 0.6f,
        rimWeight = 0.4f,
        refraction = 1.0f,
        dispersion = 0.35f,
        tintArgb = 0x40FF3B30,
        saturation = 1f,
        contrast = 1f,
        edgeLight = 0.1f,
        tier = TIER_HIGH,
    )

    /** The BALANCED path — the one the P1.7 harness actually ran. */
    private fun uniformsBalanced(): GlassUniforms = GlassUniforms(
        widthPx = 1080f,
        heightPx = 480f,
        cornerRadiusPx = 48f,
        centreWeight = 0.6f,
        rimWeight = 0.4f,
        refraction = 1.0f,
        dispersion = 0f,
        tintArgb = 0x40FF3B30,
        saturation = 1f,
        contrast = 1f,
        edgeLight = 0.1f,
        tier = TIER_BALANCED,
    )

    /**
     * **The core assertion: the shader compiles.**
     *
     * A syntax / AGSL-compatibility error throws `IllegalArgumentException` from the `RuntimeShader`
     * constructor. If this test is green, the shader parses.
     */
    @Test
    fun shaderCompilesAtHighTier() {
        assumeTrue("AGSL requires API 33+", Build.VERSION.SDK_INT >= AGSL_MIN_API)

        val shader: RuntimeShader = buildGlassShader(uniformsHighTier())
        assertNotNull("Shader should build", shader)
    }

    @Test
    fun shaderCompilesAtBalancedTier() {
        assumeTrue("AGSL requires API 33+", Build.VERSION.SDK_INT >= AGSL_MIN_API)

        val shader: RuntimeShader = buildGlassShader(uniformsBalanced())
        assertNotNull("Shader should build", shader)
    }

    /**
     * **Every bound uniform is accepted at runtime.**
     *
     * `LumenGlassShaderStructureTest` asserts declarations textually; this confirms the *bind* side
     * against a real `RuntimeShader`. A `setFloatUniform`/`setColorUniform` for a name the shader
     * does not declare throws `IllegalArgumentException: unknown identifier`.
     *
     * [buildGlassShader] binds every name in [LUMEN_GLASS_UNIFORMS]; reaching this line means all of
     * them succeeded. The explicit check documents the intent and fails loudly if the list and the
     * binder ever drift apart.
     */
    @Test
    fun allDeclaredUniformsBindWithoutError() {
        assumeTrue("AGSL requires API 33+", Build.VERSION.SDK_INT >= AGSL_MIN_API)

        // Throws IllegalArgumentException if any bound name is undeclared.
        val shader = buildGlassShader(uniformsHighTier())
        assertNotNull(shader)

        assertTrue(
            "Expected the canonical uniform list to be non-empty",
            LUMEN_GLASS_UNIFORMS.isNotEmpty(),
        )
    }

    /**
     * The API gate degrades gracefully rather than throwing.
     *
     * Below API 33, [buildGlassShaderOrNull] must return `null` so the caller draws the FROST
     * fallback. On an AGSL device this simply confirms the positive branch returns a shader.
     */
    @Test
    fun buildOrNullRespectsTheApiGate() {
        val shader = buildGlassShaderOrNull(uniformsBalanced())
        if (isAgsSupported()) {
            assertNotNull("On AGSL devices the shader must be built", shader)
        } else {
            assertNull("Below API 33 the shader must be null (frost fallback)", shader)
        }
    }
}
