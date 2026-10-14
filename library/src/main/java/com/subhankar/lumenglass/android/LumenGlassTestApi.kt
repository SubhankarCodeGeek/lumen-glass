package com.subhankar.lumenglass.android

/**
 * # Test-only entry point — not part of the public API
 *
 * The `android/` layer is `internal`, because none of it is designed API: it is the shader, the
 * uniform binding, the capture, and the diagnostic probe. Consumers use the API in the `compose/`
 * package (`rememberLumenGlassState`, `LumenSource`, `Modifier.lumenGlass`).
 *
 * Two **instrumented** tests live in `:app` and need to reach below that line:
 *
 * - `LumenGlassShaderCompileTest` — compiles the shader on a device, which no JVM test can do.
 * - `ShaderMathParityTest` — renders a diagnostic probe and compares the readback against
 *   `core/GlassMath`.
 *
 * They cannot move into `:library`'s own `androidTest`: a library's androidTest APK bundles the
 * library's whole transitive classpath including Compose material, which requires `minSdk 23`, and
 * `:library` is deliberately `minSdk 21` — the manifests cannot be reconciled (this was established
 * during P1.7 and the reason these tests live in `:app` at all).
 *
 * So rather than leave the whole `android/` layer public for two tests' convenience, the specific
 * pieces they need are re-exposed **here**, in one file, so:
 *
 * 1. the surface is small and auditable, and
 * 2. it is obvious these exist for testing and are not a supported contract.
 *
 * > [!WARNING]
 * > **Everything here may change without notice.** It exists to let the shader be verified on real
 * > hardware. Do not call it from app code; use the `compose/` API instead.
 */
@androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
public object LumenGlassTestApi {

    /**
     * Builds a bound shader exactly as the production path does.
     *
     * Exposed so the compile smoke test exercises the **production** builder rather than a copy —
     * a copy would only prove the copy compiles.
     *
     * @throws IllegalArgumentException if the shader string has a syntax or AGSL-compatibility
     *   error, which is precisely what the test asserts does not happen.
     */
    @androidx.annotation.RequiresApi(AGSL_MIN_API)
    public fun buildShader(uniforms: GlassUniforms): android.graphics.RuntimeShader =
        buildGlassShader(uniforms)

    /** Builds the shader, or `null` below API 33. See [buildGlassShaderOrNull]. */
    public fun buildShaderOrNull(uniforms: GlassUniforms): android.graphics.RuntimeShader? =
        buildGlassShaderOrNull(uniforms)

    /** Whether this device can run AGSL at all. */
    public fun isAgsSupported(): Boolean = com.subhankar.lumenglass.android.isAgsSupported()

    /** The minimum API level at which AGSL exists (33). */
    public val agslMinApi: Int get() = AGSL_MIN_API

    /** The raw AGSL source, so a test can assert structural properties of it. */
    public val shaderSource: String get() = LUMEN_GLASS_AGSL

    /** Every uniform name the Kotlin side binds. */
    public val uniformNames: List<String> get() = LUMEN_GLASS_UNIFORMS

    /** Tier uniform value meaning HIGH (dispersion path available). */
    public val tierHigh: Float get() = TIER_HIGH

    /** Tier uniform value meaning BALANCED (single content sample). */
    public val tierBalanced: Float get() = TIER_BALANCED

    // ---- the diagnostic probe (ShaderMathParityTest) ------------------------

    /** The probe shader source, for the given encode range. See `DebugProbeShader`. */
    public fun probeShaderSource(encodeRange: Float): String = buildProbeShader(encodeRange)

    /** The value range the probe maps onto the `0..1` channel range. */
    public val probeEncodeRange: Float get() = PROBE_ENCODE_RANGE

    /** Decodes a probe pixel back into a displacement in pixels. */
    public fun decodeProbeValue(channel: Float): Float =
        com.subhankar.lumenglass.android.decodeProbeValue(channel)

    /**
     * The shared maths expressions the probe and the production shader are both generated from.
     *
     * The parity test asserts both shaders contain these, so nobody can "fix" a parity failure by
     * editing one side alone.
     */
    public object ProbeExpressions {
        /** Squircle field, shared. */
        public val squircle: String get() = ProbeShaderText.SQUIRCLE_EXPR

        /** Lens weight, shared. */
        public val lensWeight: String get() = ProbeShaderText.LENS_WEIGHT_EXPR

        /** Lens scale, shared. */
        public val lensScale: String get() = ProbeShaderText.LENS_SCALE_EXPR
    }

    // ---- the overlap guard (RawRenderNodeActivity) --------------------------

    /**
     * Checks whether a glass surface overlaps the captured region. See `GlassOverlapGuard`.
     *
     * Exposed so the raw-RenderNode harness can prove the guard fires without going through the
     * Compose modifiers — that screen exists to isolate the shader from Compose entirely.
     */
    public fun checkOverlap(
        surfaceLeft: Float,
        surfaceTop: Float,
        surfaceRight: Float,
        surfaceBottom: Float,
        captureLeft: Float,
        captureTop: Float,
        captureRight: Float,
        captureBottom: Float,
    ): Verdict = checkOverlap(
        surface = OverlapRect(surfaceLeft, surfaceTop, surfaceRight, surfaceBottom),
        capture = OverlapRect(captureLeft, captureTop, captureRight, captureBottom),
    )

    /** Logs a verdict from [checkOverlap] using the library's own debug channel. */
    public fun logOverlapVerdict(verdict: Verdict) {
        logOverlapVerdict(verdict)
    }

    /** The result of [checkOverlap]: whether the surface covers the capture, and why. */
    public data class Verdict(
        public val overlapping: Boolean,
        public val intersectionFraction: Float,
        public val message: String,
    )

    /** Bridges the internal verdict type to the public [Verdict] alias. */
    private fun checkOverlap(surface: OverlapRect, capture: OverlapRect): Verdict {
        val v = com.subhankar.lumenglass.android.checkOverlap(surface, capture)
        return Verdict(v.overlapping, v.intersectionFraction, v.message)
    }

    // ---- the raw substrate (TierComparisonActivity) -------------------------

    /**
     * Creates a capture at the given tier's scale, for harnesses that drive the substrate directly.
     *
     * `TierComparisonActivity` needs this because it renders **several tiers side by side**, which
     * the consumer API does not express — one `LumenGlassState` carries one resolved tier. That is a
     * deliberate limitation of the public API, not a gap: consumers pick a tier, they do not render
     * a comparison grid.
     */
    public fun createCapture(): GlassCapture? =
        if (android.os.Build.VERSION.SDK_INT >= RENDER_NODE_MIN_API) GlassCapture() else null

    /** Records content into a capture, at the given scale. See `GlassCapture.record`. */
    @androidx.annotation.RequiresApi(RENDER_NODE_MIN_API)
    public fun recordInto(
        capture: GlassCapture,
        widthPx: Float,
        heightPx: Float,
        scale: Float,
        draw: (android.graphics.Canvas) -> Unit,
    ) {
        capture.record(left = 0f, top = 0f, right = widthPx, bottom = heightPx, scale = scale, draw = draw)
    }

    /** Applies the glass material to a capture. See `GlassCapture.applyGlass`. */
    @androidx.annotation.RequiresApi(RENDER_EFFECT_MIN_API)
    public fun applyGlass(
        capture: GlassCapture,
        uniforms: GlassUniforms,
        frostRadiusPx: Float,
    ): Boolean = applyGlassEffect(capture, uniforms, frostRadiusPx)

    /** Releases a capture created by [createCapture]. */
    public fun releaseCapture(capture: GlassCapture?) {
        if (capture != null && android.os.Build.VERSION.SDK_INT >= RENDER_NODE_MIN_API) {
            capture.release()
        }
    }

    /** Draws a capture's node onto a recording canvas. See `GlassCapture.node`. */
    @androidx.annotation.RequiresApi(RENDER_NODE_MIN_API)
    public fun drawCapture(capture: GlassCapture, canvas: android.graphics.Canvas) {
        (canvas as? android.graphics.RecordingCanvas)?.drawRenderNode(capture.node)
    }

    /** Accesses the recorded source GraphicsLayer for tests. */
    public fun getSourceLayer(state: com.subhankar.lumenglass.compose.LumenGlassState): androidx.compose.ui.graphics.layer.GraphicsLayer? =
        state.sourceLayer
}
