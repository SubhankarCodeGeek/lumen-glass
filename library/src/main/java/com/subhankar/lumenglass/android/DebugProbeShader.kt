package com.subhankar.lumenglass.android

/**
 * The **displacement probe** — a diagnostic shader used only by `ShaderMathParityTest`.
 *
 * ## What it is for
 *
 * Q-F: nothing verified that the AGSL shader numerically matches `core/GlassMath`, and on
 * inspection the two had silently drifted apart. Diffing a *composited* glass image would mostly
 * measure the harness (colour management, premultiplied alpha, driver variance). Instead this
 * probe renders the **displacement vector itself** to pixels, which the test reads back and
 * compares against `GlassMath.lensOffset`.
 *
 * ## Why the maths is *not* duplicated here
 *
 * If this file re-implemented the displacement, the test would only prove that two copies of the
 * maths agree with each other — worthless as a check on the production shader.
 *
 * Instead the probe is **assembled from the same expression text** the production shader uses
 * ([ProbeShaderText.SQUIRCLE_EXPR], [ProbeShaderText.LENS_WEIGHT_EXPR],
 * [ProbeShaderText.LENS_SCALE_EXPR]), substituted into both via [buildProbeShader] and
 * [buildGlassShaderSource]. There is exactly one copy of each expression, in this file, and both
 * shaders are generated from it. A change to the maths therefore flows into the production shader
 * *and* the probe, and a disagreement with `GlassMath` fails the parity test.
 *
 * @see LUMEN_GLASS_AGSL
 * @see buildProbeShader
 *
 * ## API status: PROVISIONAL (pre-P4)
 *
 * Public so the instrumented parity test (in `:app`) can reach it. Not a designed API.
 */
internal object ProbeShaderText {

    /**
     * The squircle field expression, shared with the production shader.
     *
     * `n` (normalized `-1..1` coords) and `e` (the superellipse exponent, a parameter or uniform
     * named `e` in both call sites) must be in scope.
     * Mirrors `GlassMath.squircle`.
     */
    const val SQUIRCLE_EXPR: String =
        "pow(abs(n.x), e) + pow(abs(n.y), e)"

    /**
     * The lens weight expression, shared with the production shader.
     *
     * `sq`, `centreWeight`, `rimWeight` and `refraction` must be in scope.
     * Mirrors the weight term in `GlassMath.lensOffset`.
     */
    const val LENS_WEIGHT_EXPR: String =
        "((1.0 - sq) * centreWeight + sq * rimWeight) * refraction"

    /**
     * The lens scale term, shared with the production shader.
     *
     * `halfSize` must be in scope. Mirrors the `min` that `GlassMath.lensOffset` performs
     * internally (Q-I).
     */
    const val LENS_SCALE_EXPR: String = "min(halfSize.x, halfSize.y)"
}

/**
 * Builds the displacement-probe shader.
 *
 * Output encoding: the displacement in pixels is written to the **red and green** channels mapped
 * from `[-RANGE, +RANGE]` onto `[0, 1]`, so an 8-bit readback resolves to roughly
 * `2 * RANGE / 255` per step. The test sizes [PROBE_ENCODE_RANGE] to the surface so the encoding
 * resolution is comfortably inside the assertion tolerance — see the test for the arithmetic.
 *
 * A `uniform float2 probeScale` is **not** used; the encode range is baked in so the readback
 * decoder and the shader cannot disagree about it.
 */
internal fun buildProbeShader(encodeRange: Float): String = """
uniform float2 size;
uniform float  cornerRadius;
uniform float  centreWeight;
uniform float  rimWeight;
uniform float  squircleExp;
uniform float  refraction;

// Encodes a signed pixel value into 0..1 for an 8-bit channel.
float encodeSigned(float v) {
    return clamp(v / (2.0 * float(${encodeRange})) + 0.5, 0.0, 1.0);
}

half4 main(float2 fragCoord) {
    float2 centre = size * 0.5;
    float2 halfSize = max(centre, float2(1.0));
    float2 p = fragCoord - centre;

    float2 n = p / halfSize;
    // Named `e` to match ProbeShaderText.SQUIRCLE_EXPR, which is interpolated verbatim below and
    // in the production shader's `squircle()` helper.
    float e = squircleExp;
    float sq = clamp(${ProbeShaderText.SQUIRCLE_EXPR}, 0.0, 1.0);

    float weight = ${ProbeShaderText.LENS_WEIGHT_EXPR};
    float scale = ${ProbeShaderText.LENS_SCALE_EXPR};
    float2 offset = -n * weight * scale;

    return half4(half(encodeSigned(offset.x)), half(encodeSigned(offset.y)), 0.0, 1.0);
}
""".trimIndent()

/**
 * The value range the probe maps onto the `0..1` channel range.
 *
 * Chosen to comfortably exceed any realistic displacement for the test's surface, so the encode
 * never clips (clipping would make a *large* error look like a *small* one — the worst possible
 * failure mode for a parity test).
 */
internal const val PROBE_ENCODE_RANGE: Float = 256f

/**
 * Decodes a probe pixel back into a displacement in pixels.
 *
 * The exact inverse of the shader's `encodeSigned`, so the two cannot drift.
 */
internal fun decodeProbeValue(channel: Float): Float =
    (channel - 0.5f) * (2f * PROBE_ENCODE_RANGE)
