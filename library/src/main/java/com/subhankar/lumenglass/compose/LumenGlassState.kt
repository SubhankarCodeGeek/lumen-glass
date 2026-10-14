package com.subhankar.lumenglass.compose

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.subhankar.lumenglass.android.GlassCapture
import com.subhankar.lumenglass.core.GlassFeatures
import com.subhankar.lumenglass.core.QualityTier
import com.subhankar.lumenglass.core.resolveFeatures
import com.subhankar.lumenglass.core.selectTier

/**
 * Holds the state shared between [LumenSource] and [Modifier.lumenGlass].
 *
 * A glass surface needs two things that must agree:
 *
 * 1. **Captured content** — the content *behind* the glass, recorded into a layer ([LumenSource]).
 * 2. **The material** — the shader that refracts that content ([lumenGlass]).
 *
 * This object is that shared point, which is why both modifiers take the same instance.
 *
 * ## The overlap requirement (read this — it is the one gotcha)
 *
 * > [!IMPORTANT]
 * > **The `lumenGlass` surface MUST overlap the region marked with `LumenSource`.**
 * >
 * > If it does not, the shader samples empty space and renders **near-black** — a surface that
 * > looks broken rather than like glass. This is the single most common way to misuse the library,
 * > and it is a *layout* mistake, not a configuration one: the two modifiers must genuinely overlap
 * > on screen.
 * >
 * > In debug builds the library logs a warning naming both rectangles when it detects a
 * > non-overlap, so the cause is visible rather than mysterious. See
 * > [LumenSource] for a worked example.
 *
 * ## Quality
 *
 * The [QualityTier] is chosen once, from [QualityTier] capability plus any override passed to
 * [`rememberLumenGlassState`]. **The tier that was actually applied is always queryable** via
 * [resolvedFeatures] — the library never silently drops below what was asked for without the app
 * being able to tell (`PLAN.md` §5.2). Check `resolvedFeatures.useDispersion` (etc.) if you need to
 * adapt your UI to a downgrade.
 */
public class LumenGlassState internal constructor(
    /**
     * The tier in effect after capability selection and any overrides.
     *
     * This is what the render path actually uses — not necessarily what was requested. Compare it
     * against your request to detect a downgrade.
     */
    public val tier: QualityTier,
) {
    /**
     * The concrete effect set [tier] enables.
     *
     * This is the **detectability contract** made public: an app can ask "did I actually get
     * dispersion / blur / bezel?" rather than inferring it. See `PLAN.md` §5.2.
     */
    public val resolvedFeatures: GlassFeatures = tier.resolveFeatures()

    /**
     * The capture that `lumenSource` records into and `lumenGlass` samples.
     *
     * `internal` on purpose — it is an implementation detail of the two modifiers, not something
     * callers should manipulate. They coordinate on it; consumers just pass the state around.
     */
    internal var capture: GlassCapture? = null

    /**
     * The Compose [androidx.compose.ui.graphics.layer.GraphicsLayer] recorded by [LumenSource]
     * and sampled by [LumenGlassNode].
     */
    internal var sourceLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null

    /**
     * Where the captured content sits, in **screen (root) coordinates**.
     *
     * Set by `lumenSource`, read by `lumenGlass` so the overlap guard can compare the two in a
     * common space. `null` until each has been laid out once.
     *
     * Screen coordinates matter: comparing each rect against its own origin makes every
     * correctly-sized pair look fully overlapped, which is a false negative in the exact check that
     * exists to catch ARCHITECTURE §4.
     */
    internal var sourceBounds: androidx.compose.ui.geometry.Rect? = null

    /** Where the glass surface sits, in **screen (root) coordinates**. See [sourceBounds]. */
    internal var surfaceBounds: androidx.compose.ui.geometry.Rect? = null

    /** Whether the underlying capture can run at all on this device (AGSL present). */
    public val canRunShader: Boolean get() = resolvedFeatures.useShader
}

/**
 * Creates and remembers a [LumenGlassState] for the current composition, releasing its capture when
 * the composition leaves.
 *
 * This is the entry point for both modifiers:
 *
 * ```kotlin
 * val glass = rememberLumenGlassState()
 *
 * Box {
 *     LumenSource(glass) {                           // marks what the glass refracts
 *         ScrollingContent()
 *     }
 *     Card(
 *         Modifier.lumenGlass(glass, cornerRadius = 16.dp) { // the material itself
 *             lens       = LensModel.Blend(centre = 0.6f, rim = 0.4f)
 *             refraction = 1.0f
 *             frost      = 6.dp
 *         }
 *     )
 * }
 * ```
 *
 * @param quality force a tier instead of letting the capability probe choose. Useful for battery
 *   (`QualityTier.LOW`) or a demo (`QualityTier.HIGH`). Pass `null` (the default) to let the library
 *   decide — and check [LumenGlassState.resolvedFeatures] to see what it decided.
 */
@Composable
public fun rememberLumenGlassState(quality: QualityTier? = null): LumenGlassState {
    // Density is captured here so the modifiers can convert dp -> px without re-reading it.
    val density = LocalDensity.current

    val state = remember(quality, density) {
        val apiLevel = Build.VERSION.SDK_INT
        val tier = selectTier(apiLevel = apiLevel, requestedTier = quality)
        LumenGlassState(tier = tier)
    }

    // The capture owns a native RenderNode, so it must be released when the composition goes away
    // or it leaks. `DisposableEffect` scoped to the state instance.
    DisposableEffect(state) {
        onDispose {
            // `release()` is API 29+ (unlike the rest of the capture path, which is gated at 33 by
            // AGSL). On older devices no capture was ever created, so there is nothing to release.
            state.sourceLayer = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                state.capture?.release()
            }
            state.capture = null
        }
    }

    return state
}
