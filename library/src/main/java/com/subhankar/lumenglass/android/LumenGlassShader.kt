package com.subhankar.lumenglass.android

/**
 * # The Lumen Glass AGSL shader (CLEAN-ROOM)
 *
 * This shader is **re-derived from the mathematics** in `core/GlassMath.kt` — the rounded-rect SDF,
 * the aspect-agnostic squircle field, the centre/rim lens displacement, and the per-channel
 * dispersion scales. It is **not** a copy of any third-party glass shader. See `CLEANROOM.md`.
 *
 * `core/GlassMath` is the **specification** for the maths; this shader implements it, and
 * `ShaderMathParityTest` (instrumented, in `:app`) enforces the agreement numerically on device.
 *
 * It runs as a `RenderEffect.createRuntimeShaderEffect(shader, "content")` over a captured backdrop
 * layer — a `uniform shader content` that can be sampled at arbitrary coordinates.
 *
 * ## Tiering
 *
 * The `tier` uniform selects the code path so one shader serves all devices:
 * - `2` = HIGH — dispersion on (3 content samples), bezel handled by the caller.
 * - `1` = BALANCED/LOW — single content sample; blur is done in the capture (separable, caller-side).
 * - `0` = reserved (FALLBACK draws no shader at all; the caller uses a frost panel).
 *
 * ## Uniforms (all asserted by `LumenGlassShaderStructureTest`)
 *
 * | uniform | meaning |
 * |---|---|
 * | `content` | the captured backdrop (the shader input) |
 * | `size` | surface size in px |
 * | `cornerRadius` | rounded-corner radius in px |
 * | `centreWeight` | whole-face lens weight `0..1` |
 * | `rimWeight` | rim lens weight `0..1` |
 * | `squircleExp` | superellipse exponent (e.g. `8`) |
 * | `refraction` | overall displacement strength |
 * | `dispersion` | chromatic-aberration amount (`0` = off) |
 * | `tint` | material tint (ARGB) |
 * | `saturation` | colour saturation multiplier |
 * | `contrast` | colour contrast multiplier |
 * | `edgeLight` | specular edge-band strength |
 * | `tier` | quality tier selector |
 *
 * ## API status: PROVISIONAL (pre-P4)
 *
 * Public so the instrumented parity test (in `:app`) can assert the shared-expression wiring.
 * Not a designed API.
 */
internal val LUMEN_GLASS_AGSL: String = """
uniform shader content;
uniform float2 size;
uniform float  cornerRadius;
uniform float  centreWeight;
uniform float  rimWeight;
uniform float  squircleExp;
uniform float  refraction;
uniform float  dispersion;
layout(color) uniform half4 tint;
uniform float  saturation;
uniform float  contrast;
uniform float  edgeLight;
uniform float  tier;
// Bezel (inner shadow). Gated on the HIGH tier; disabled by passing bezelAlpha = 0.
uniform float  bezelAlpha;
uniform float  bezelRadiusPx;

// Signed distance to a rounded rectangle (<= 0 inside). Mirrors GlassMath.roundedRectSdf.
float roundedRectSdf(float2 p, float2 halfSize, float radius) {
    float r = min(radius, min(halfSize.x, halfSize.y));
    float2 q = abs(p) - (halfSize - float2(r));
    float outside = length(max(q, float2(0.0)));
    float inside = min(max(q.x, q.y), 0.0);
    return outside + inside - r;
}

// Aspect-agnostic squircle distance: 0 at centre, ~1 at the border. Mirrors GlassMath.squircle.
//
// The body is interpolated from ProbeShaderText.SQUIRCLE_EXPR, so this shader and the diagnostic
// probe are generated from ONE copy of the expression and cannot drift apart (see Q-F).
float squircle(float2 n, float e) {
    return ${ProbeShaderText.SQUIRCLE_EXPR};
}

// Colour grading: saturation then contrast. Keeps the material from reading as flat.
//
// NOTE: AGSL does NOT accept GLSL half-suffix literals (`0.5h`) — the device compiler rejects
// them. Use the `half(...)` constructor instead. This was caught only by compiling on a real
// device; the structural guard test checks uniform *declarations*, not syntax.
half3 gradeColor(half3 c) {
    float luma = dot(c, half3(0.2126, 0.7152, 0.0722));
    half3 saturated = mix(half3(luma), c, half(saturation));
    return saturate((saturated - half(0.5)) * half(contrast) + half(0.5));
}

half4 main(float2 fragCoord) {
    float2 centre = size * 0.5;
    float2 halfSize = max(centre, float2(1.0));
    float2 p = fragCoord - centre;

    // Rounded-rect mask (anti-aliased via a small SDF feather).
    //
    // NOTE: AGSL has NO `fwidth()` / derivative builtins (device compiler: "no match for
    // fwidth(float)"). We feather with a fixed ~1px band instead. This is the correct
    // trade-off for AGSL: screen-space derivatives are simply unavailable here.
    float sdf = roundedRectSdf(p, halfSize, cornerRadius);
    float aa = 1.0;
    float coverage = 1.0 - smoothstep(-aa, aa, sdf);
    if (coverage <= 0.0) {
        return half4(0.0);
    }

    // Aspect-agnostic normalized coords (-1..1 per axis) + squircle field.
    //
    // CONTRACT: the terms below are specified by `core/GlassMath` and enforced numerically on
    // device by `ShaderMathParityTest` (in :app), which renders a probe and compares the readback
    // against `GlassMath.lensOffset` / `GlassMath.dispersionScales`. If you change the maths here,
    // change it there first — Q-F exists precisely because these two silently drifted apart.
    float2 n = p / halfSize;
    float sq = clamp(squircle(n, squircleExp), 0.0, 1.0);

    // Lens displacement: centre term peaks in the middle, rim term peaks at the edge.
    //
    // The weight and scale terms are interpolated from ProbeShaderText, so this shader and the
    // diagnostic probe are generated from ONE copy of each expression (see Q-F). Scale is
    // min(halfSize.x, halfSize.y) — the same `min` GlassMath.lensOffset performs internally (Q-I).
    float weight = ${ProbeShaderText.LENS_WEIGHT_EXPR};
    float scale = ${ProbeShaderText.LENS_SCALE_EXPR};
    float2 offset = -n * weight * scale;
    float2 sampleCoord = fragCoord + offset;

    // Sample, with optional chromatic dispersion (3 taps) on the HIGH tier.
    //
    // Dispersion is a RADIAL SCALE about the surface centre, per GlassMath.dispersionScales:
    //   redScale = 1 + dispersion, greenScale = 1, blueScale = 1 - dispersion
    // Each channel samples the (lens-displaced) coordinate scaled about `centre` by its own
    // factor. This is zero at the optical centre and grows toward the rim.
    //
    // NOTE: this replaced an earlier linear-offset formulation that disagreed with GlassMath — the
    // drift Q-F uncovered. Do not reintroduce an offset-based variant.
    half4 color;
    if (tier >= 2.0 && dispersion > 0.0) {
        float redScale = 1.0 + dispersion;
        float blueScale = 1.0 - dispersion;
        float2 rel = sampleCoord - centre;
        half4 cR = content.eval(centre + rel * redScale);
        half4 cG = content.eval(sampleCoord);
        half4 cB = content.eval(centre + rel * blueScale);
        color = half4(cR.r, cG.g, cB.b, cG.a);
    } else {
        color = content.eval(sampleCoord);
    }

    // Colour grading, then the material tint.
    color.rgb = gradeColor(color.rgb);
    color.rgb = mix(color.rgb, tint.rgb, tint.a);

    // Edge / specular lighting: brightest where the SDF normal faces the light.
    float edgeBand = smoothstep(-max(edgeLight, 1e-4) * 24.0, 0.0, sdf);
    color.rgb += half3(edgeBand * edgeLight);

    // Bezel — a soft dark inner edge that gives the glass depth. Derived from the SAME `sdf` the
    // mask uses, so it costs almost nothing: dark where the pixel is just inside the border,
    // fading to nothing toward the middle. Gated on the HIGH tier, and a no-op when bezelAlpha
    // is 0 (which is what the cheaper tiers pass).
    if (tier >= 2.0 && bezelAlpha > 0.0) {
        // `sdf` is <= 0 inside. A band of width bezelRadiusPx inside the border:
        //   sdf = 0 at the edge  ->  strongest shadow
        //   sdf = -bezelRadiusPx ->  no shadow
        float bezel = 1.0 - smoothstep(-max(bezelRadiusPx, 1e-4), 0.0, sdf);
        color.rgb *= half(1.0 - bezel * bezelAlpha);
    }

    return color * coverage;
}
""".trimIndent()

/**
 * Names of every uniform the Kotlin side binds on [LUMEN_GLASS_AGSL].
 *
 * `LumenGlassShaderStructureTest` asserts each of these is **declared** in the shader string. A
 * `setFloatUniform`/`setColorUniform` for an undeclared name throws
 * `IllegalArgumentException: unknown identifier` at runtime — a crash class the Kotlin compiler and
 * Robolectric cannot catch. This list closes it at test time.
 *
 * ## API status: PROVISIONAL (pre-P4)
 *
 * Public so the instrumented compile/bind smoke test can assert against it. Not the designed public
 * API — that is P4.
 */
internal val LUMEN_GLASS_UNIFORMS: List<String> = listOf(
    "size",
    "cornerRadius",
    "centreWeight",
    "rimWeight",
    "squircleExp",
    "refraction",
    "dispersion",
    "tint",
    "saturation",
    "contrast",
    "edgeLight",
    "tier",
    "bezelAlpha",
    "bezelRadiusPx",
)
