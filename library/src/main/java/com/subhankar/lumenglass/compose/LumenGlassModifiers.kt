package com.subhankar.lumenglass.compose

import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import com.subhankar.lumenglass.android.GlassUniforms
import com.subhankar.lumenglass.android.RENDER_EFFECT_MIN_API
import com.subhankar.lumenglass.android.TIER_BALANCED
import com.subhankar.lumenglass.android.TIER_HIGH
import com.subhankar.lumenglass.android.buildGlassRenderEffectOrNull
import com.subhankar.lumenglass.android.checkOverlap
import com.subhankar.lumenglass.android.logOverlapVerdict
import com.subhankar.lumenglass.android.toOverlapRect
import com.subhankar.lumenglass.core.GlassSpec

/**
 * Places content so that the glass can refract it.
 *
 * Wrap the content that sits **behind** the glass:
 *
 * ```kotlin
 * val glass = rememberLumenGlassState()
 *
 * Box {
 *     LumenSource(glass) {                 // gets recorded into the capture
 *         ScrollingContent()
 *     }
 *     Card(Modifier.lumenGlass(glass) { refraction = 1.2f })
 * }
 * ```
 *
 * ## Architecture
 *
 * Under the hood, this composable records the subtree into a Compose 1.7 [GraphicsLayer]
 * and draws it to the screen. The overlay [lumenGlass] modifier accesses this layer and samples
 * it through the AGSL refraction shader.
 *
 * ## The overlap requirement
 *
 * > [!IMPORTANT]
 * > **This region and the `lumenGlass` surface must overlap on screen**, or the shader samples
 * > empty space and the glass renders **near-black**. Both must genuinely occupy the same pixels.
 * > Debug builds log a warning naming both rectangles when they do not.
 *
 * On devices without AGSL (below API 33) this simply renders [content] normally — there is no shader
 * to feed.
 *
 * @param state the shared state from `rememberLumenGlassState`.
 * @param content the content to be refracted by the glass surface.
 */
@Composable
public fun LumenSource(
    state: LumenGlassState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!state.canRunShader) {
        Box(modifier) { content() }
        return
    }

    val layer = rememberGraphicsLayer()
    state.sourceLayer = layer

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                state.sourceBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                layer.record {
                    this@drawWithContent.drawContent()
                }
                drawLayer(layer)
            },
    ) {
        content()
    }
}

/**
 * Draws the glass material.
 *
 * ```kotlin
 * Modifier.lumenGlass(glass) {
 *     lens       = LensModel.Blend(centre = 0.6f, rim = 0.4f)
 *     refraction = 1.0f
 *     frost      = 6.dp
 *     tint       = Color.White.copy(alpha = 0.15f)
 * }
 * ```
 *
 * Every field has a sensible default, so `Modifier.lumenGlass(state)` alone renders a reasonable
 * glass.
 *
 * > [!IMPORTANT]
 * > **This surface must overlap the region placed with [LumenSource]** or it renders near-black.
 * > See [LumenGlassState].
 *
 * On devices without AGSL this draws a **frosted panel** instead — a documented degradation meant to
 * look deliberate, not broken (`PLAN.md` §5.2). The library never silently renders nothing.
 *
 * @param state the shared state from `rememberLumenGlassState`.
 * @param spec configure the optics here. See [GlassSpecBuilder].
 */
public fun Modifier.lumenGlass(
    state: LumenGlassState,
    spec: GlassSpecBuilder.() -> Unit = {},
): Modifier = this
    // Record this surface's own rect in SCREEN coordinates, so the overlap guard compares it with
    // the source rect in a common space.
    .onGloballyPositioned { coordinates -> state.surfaceBounds = coordinates.boundsInRoot() }
    .then(LumenGlassElement(state, GlassSpecBuilder().apply(spec)))

/**
 * Draws the glass material over the backdrop recorded by [LumenSource].
 *
 * When no shader can run, this draws a frosted panel — the documented degraded appearance.
 */
internal class LumenGlassNode(
    private val state: LumenGlassState,
    private var spec: GlassSpec,
) : androidx.compose.ui.Modifier.Node(), DrawModifierNode {

    private var glassLayer: GraphicsLayer? = null

    fun updateSpec(newSpec: GlassSpec) {
        spec = newSpec
    }

    override fun onDetach() {
        glassLayer?.let { layer ->
            requireGraphicsContext().releaseGraphicsLayer(layer)
        }
        glassLayer = null
        super.onDetach()
    }

    @RequiresApi(RENDER_EFFECT_MIN_API)
    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        val sourceLayer = state.sourceLayer
        val sourceBounds = state.sourceBounds
        val surfaceBounds = state.surfaceBounds

        // Below AGSL, or before the source has recorded: show a deliberate frost panel rather than
        // nothing at all. A blank surface would read as a bug.
        if (sourceLayer == null || sourceBounds == null || surfaceBounds == null || !state.canRunShader) {
            drawFrostPanel()
            drawContent()
            return
        }

        // Surface the ARCHITECTURE §4 pitfall through the public API, so a consumer cannot silently
        // get near-black glass with no explanation.
        reportOverlap()

        val uniforms = GlassUniforms.from(
            spec = spec,
            widthPx = size.width,
            heightPx = size.height,
            cornerRadiusPx = 0f,
            dispersion = if (state.resolvedFeatures.useDispersion) spec.dispersion else 0f,
            refraction = spec.refraction,
            tier = if (state.resolvedFeatures.useDispersion) TIER_HIGH else TIER_BALANCED,
            bezelRadiusPx = if (state.resolvedFeatures.useDispersion) (spec.bezel?.radiusDp ?: 0f) * density else 0f,
        )

        val effect = buildGlassRenderEffectOrNull(
            uniforms = uniforms,
            frostRadiusPx = if (state.resolvedFeatures.useBlur) spec.frostDp * density else 0f,
        )
        if (effect == null) {
            drawFrostPanel()
            drawContent()
            return
        }

        var layer = glassLayer
        if (layer == null) {
            layer = requireGraphicsContext().createGraphicsLayer()
            glassLayer = layer
        }

        layer.renderEffect = effect.asComposeRenderEffect()

        val offsetX = surfaceBounds.left - sourceBounds.left
        val offsetY = surfaceBounds.top - sourceBounds.top

        layer.record {
            translate(-offsetX, -offsetY) {
                drawLayer(sourceLayer)
            }
        }

        drawLayer(layer)
        drawContent()
    }

    /**
     * Checks that this surface overlaps the captured region, warning (in debug) when it does not.
     *
     * Both rects are in **screen coordinates** (recorded via `onGloballyPositioned`), so this tests
     * real on-screen overlap — not merely that two correctly-sized boxes exist.
     */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.reportOverlap() {
        val source = state.sourceBounds ?: return
        val surface = state.surfaceBounds ?: return
        logOverlapVerdict(
            checkOverlap(
                surface.toAndroidRectF().toOverlapRect(),
                source.toAndroidRectF().toOverlapRect(),
            ),
        )
    }

    /** The documented degraded appearance when no shader can run. */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFrostPanel() {
        drawRect(color = Color.White.copy(alpha = FROST_PANEL_ALPHA), size = size)
    }

    private companion object {
        /**
         * Deliberately visible but not opaque: the panel must read as *frosted glass* — you should
         * be able to tell something is behind it. An opaque panel would look like a bug.
         */
        const val FROST_PANEL_ALPHA = 0.28f
    }
}

/** Bridges a Compose [Rect] to the [android.graphics.RectF] the overlap maths works in. */
private fun Rect.toAndroidRectF(): android.graphics.RectF =
    android.graphics.RectF(left, top, right, bottom)

/** `ModifierNodeElement` for [LumenGlassNode]. */
internal data class LumenGlassElement(
    private val state: LumenGlassState,
    private val builder: GlassSpecBuilder,
) : ModifierNodeElement<LumenGlassNode>() {
    override fun create(): LumenGlassNode = LumenGlassNode(state, builder.build(density = 1f))
    override fun update(node: LumenGlassNode) {
        node.updateSpec(builder.build(density = 1f))
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "lumenGlass"
    }
}
