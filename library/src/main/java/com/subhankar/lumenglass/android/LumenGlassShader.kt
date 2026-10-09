package com.subhankar.lumenglass.android

/**
 * # The Lumen Glass AGSL shader (CLEAN-ROOM)
 *
 * This shader is **re-derived from the mathematics** in `core/GlassMath.kt` — the rounded-rect SDF,
 * the aspect-agnostic squircle field, the centre/rim lens displacement, and the per-channel
 * dispersion offsets. It is **not** a copy of any third-party glass shader. See `CLEANROOM.md`.
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

// Signed distance to a rounded rectangle (<= 0 inside). Mirrors GlassMath.roundedRectSdf.
float roundedRectSdf(float2 p, float2 halfSize, float radius) {
    float r = min(radius, min(halfSize.x, halfSize.y));
    float2 q = abs(p) - (halfSize - float2(r));
    float outside = length(max(q, float2(0.0)));
    float inside = min(max(q.x, q.y), 0.0);
    return outside + inside - r;
}

// Aspect-agnostic squircle distance: 0 at centre, ~1 at the border. Mirrors GlassMath.squircle.
float squircle(float2 n, float e) {
    return pow(abs(n.x), e) + pow(abs(n.y), e);
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
    float2 n = p / halfSize;
    float sq = clamp(squircle(n, squircleExp), 0.0, 1.0);

    // Lens displacement: centre term peaks in the middle, rim term peaks at the edge.
    float weight = ((1.0 - sq) * centreWeight + sq * rimWeight) * refraction;
    float2 offset = -n * weight * min(halfSize.x, halfSize.y);
    float2 sampleCoord = fragCoord + offset;

    // Sample, with optional chromatic dispersion (3 taps) on the HIGH tier.
    half4 color;
    if (tier >= 2.0 && dispersion > 0.0) {
        float2 fromCentre = n;
        float2 aberration = dispersion * fromCentre * 0.05 * min(halfSize.x, halfSize.y);
        half4 cR = content.eval(sampleCoord - aberration);
        half4 cG = content.eval(sampleCoord);
        half4 cB = content.eval(sampleCoord + aberration);
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
)
