package com.securnyx360.lumenglass.android

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guard for the clean-room [LUMEN_GLASS_AGSL] shader.
 *
 * AGSL shaders are plain strings compiled at runtime by `RuntimeShader`. A `setFloatUniform("x", …)`
 * for a uniform the shader does **not** declare throws `IllegalArgumentException: unknown identifier`
 * — a crash the Kotlin compiler cannot catch and Robolectric cannot reproduce (no GPU). This test
 * closes that gap: every uniform bound by the backend ([LUMEN_GLASS_UNIFORMS]) must be declared.
 */
class LumenGlassShaderStructureTest {

    @Test
    fun everyBoundUniformIsDeclared() {
        val declared = Regex("""uniform\s+(?:shader|\w+)\s+(\w+)\s*;""")
            .findAll(LUMEN_GLASS_AGSL)
            .map { it.groupValues[1] }
            .toSet()

        val missing = LUMEN_GLASS_UNIFORMS.filterNot { it in declared }

        assertTrue(
            "Bound-but-undeclared uniforms (would throw 'unknown identifier' at runtime): " +
                "$missing. Declared: $declared",
            missing.isEmpty(),
        )
    }

    @Test
    fun shaderDeclaresTheContentSampler() {
        assertTrue(
            "LUMEN_GLASS_AGSL must declare `uniform shader content;`",
            Regex("""uniform\s+shader\s+content\s*;""").containsMatchIn(LUMEN_GLASS_AGSL),
        )
    }

    @Test
    fun shaderHasExactlyOneMainEntryPoint() {
        val count = Regex("""half4\s+main\s*\(\s*float2\s+\w+\s*\)""")
            .findAll(LUMEN_GLASS_AGSL)
            .count()
        assertTrue("Expected exactly one `half4 main(float2 …)`, found $count", count == 1)
    }
}
