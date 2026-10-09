package com.subhankar.lumenglass.compose

import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import com.subhankar.lumenglass.android.GlassCapture
import com.subhankar.lumenglass.android.GlassUniforms
import com.subhankar.lumenglass.android.RENDER_EFFECT_MIN_API
import com.subhankar.lumenglass.android.RENDER_NODE_MIN_API
import com.subhankar.lumenglass.android.TIER_BALANCED
import com.subhankar.lumenglass.android.TIER_HIGH
import com.subhankar.lumenglass.android.applyGlassEffect
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
 * ## Why this is a composable taking a lambda, rather than a bare `Modifier`
 *
 * Recording Compose content into a capture layer requires re-emitting a draw pass, and a draw pass
 * only exists inside a Compose draw scope. A plain `Modifier` cannot obtain one. Taking the content
 * as a lambda gives the modifier the scope it needs, and keeps the consumer's code simple — the
 * alternative would push capture plumbing onto every caller.
 *
 * (The design also cannot use Compose's `rememberGraphicsLayer()`: `GraphicsLayer` exposes no
 * `RenderNode`, and the only route to one is an internal Compose API. Lumen Glass therefore owns
 * its capture, consistent with the Q2 substrate decision.)
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
    // Pre-AGSL there is nothing to record into: draw the content plainly and skip the capture
    // entirely. This keeps the common case free of a layer that would never be sampled.
    if (!state.canRunShader) {
        Box(modifier) { content() }
        return
    }

    val capture = remember(state) { GlassCapture().also { state.capture = it } }

    // The capture owns a native node; release it when this composition leaves.
    DisposableEffect(capture) {
        onDispose {
            capture.release()
            if (state.capture === capture) state.capture = null
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size: IntSize ->
                // Record the region in the coordinate space the glass surface reports, so the
                // overlap guard compares like with like.
                state.sourceBounds =
                    Rect(Offset.Zero, Offset(size.width.toFloat(), size.height.toFloat()))
            }
            .drawWithContent {
                state.sourceBounds = Rect(Offset.Zero, size)

                // Draw this subtree's content normally — the glass surface composites over it, and
                // the region must be visible even if the surface is mispositioned.
                drawContent()

                // Re-emit the SAME content into the capture, so the shader has something to refract.
                //
                // The content can only be re-emitted from within a draw scope, which is why
                // [LumenSource] takes a content lambda. A `CanvasDrawScope` re-targets this scope's
                // draw at the capture's recording canvas — that is the supported way to run Compose
                // drawing against a foreign canvas, and it avoids depending on any Compose internal.
                capture.record(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    scale = state.resolvedFeatures.captureScale,
                ) { recordCanvas ->
                    val captureScope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
                    captureScope.draw(
                        density = this,
                        layoutDirection = layoutDirection,
                        canvas = androidx.compose.ui.graphics.Canvas(recordCanvas),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height),
                    ) {
                        // `this` is a DrawScope aimed at the recording canvas. Re-emitting the
                        // subtree's content here is what puts it in the capture.
                        this@drawWithContent.drawContent()
                    }
                }
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
): Modifier = this then LumenGlassElement(state, GlassSpecBuilder().apply(spec))

/**
 * Draws the glass material over the capture that [LumenSource] recorded.
 *
 * When no shader can run, this draws a frosted panel — the documented degraded appearance.
 */
internal class LumenGlassNode(
    private val state: LumenGlassState,
    private val spec: GlassSpec,
) : androidx.compose.ui.Modifier.Node(), DrawModifierNode {

    @RequiresApi(RENDER_EFFECT_MIN_API)
    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        val capture = state.capture

        // Below AGSL, or before the source has recorded: show a deliberate frost panel rather than
        // nothing at all. A blank surface would read as a bug.
        if (capture == null || !state.canRunShader) {
            drawContent()
            drawFrostPanel()
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
            tier = if (state.resolvedFeatures.useDispersion) TIER_HIGH else TIER_BALANCED,
        )

        val applied = applyGlassEffect(
            capture = capture,
            uniforms = uniforms,
            frostRadiusPx = if (state.resolvedFeatures.useBlur) spec.frostDp else 0f,
        )
        if (!applied) {
            drawContent()
            drawFrostPanel()
            return
        }

        // The capture's node carries the effect chain (blur -> lens). Drawing the node renders the
        // finished material. `RenderNode` is drawn by its parent canvas, which on a
        // hardware-accelerated Compose canvas is the same recording canvas.
        drawIntoCanvas { canvas ->
            (canvas.nativeCanvas as? android.graphics.RecordingCanvas)
                ?.drawRenderNode(capture.node)
        }
    }

    /**
     * Checks that this surface overlaps the captured region, warning (in debug) when it does not.
     */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.reportOverlap() {
        val source = state.sourceBounds ?: return
        val surface = Rect(Offset.Zero, size)
        // `toOverlapRect()` extends android.graphics.RectF, and the pure overlap maths lives in the
        // framework-free `android/` layer, so bridge across here.
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
    override fun update(node: LumenGlassNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "lumenGlass"
    }
}
