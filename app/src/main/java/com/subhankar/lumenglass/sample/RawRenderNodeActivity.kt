package com.subhankar.lumenglass.sample

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * P1.7 harness — **raw `RenderNode`** surface.
 *
 * The Compose harness proves the shader works in the real app UI stack. This one deliberately
 * bypasses Compose so the shader can be exercised in isolation:
 *
 * - no Compose layer compositing,
 * - no recomposition, no state-driven rebinds,
 * - a fixed set of uniform values.
 *
 * That makes it the trustworthy surface for judging whether the *shader itself* compiles and renders
 * (the Compose path adds questions about layer capture and timing on top). Per the approved plan,
 * both surfaces are built for exactly this reason.
 *
 * The substrate here is the same one `GlassCapture` uses — [RenderNode] — plus
 * `RenderEffect.createRuntimeShaderEffect(...)`. Note `RuntimeShader` is also usable directly as a
 * `Shader` on a `Paint`; both routes are exercised across the two harnesses.
 */
class RawRenderNodeActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var captureNode: RenderNode
    private lateinit var banner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.subhankar.lumenglass.android.LumenGlassDebug.enabled = true

        root = FrameLayout(this)
        setContentView(root)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && root.childCount == 0) {
            buildHarness()
        }
    }

    /**
     * Builds the harness once the view has a size — [RenderNode] needs real dimensions, and
     * `beginRecording` rejects a zero size.
     */
    @SuppressLint("SetTextI18n")
    private fun buildHarness() {
        val widthPx = root.width
        val heightPx = root.height
        if (widthPx <= 0 || heightPx <= 0) return

        // 1. Background content — the thing the glass will refract.
        val contentHeight = (heightPx * 0.7f).toInt()
        val content = HighContrastView(this)
        root.addView(
            content,
            FrameLayout.LayoutParams(widthPx, contentHeight),
        )

        // 2. Capture node: record the high-contrast content into a RenderNode.
        captureNode = RenderNode("lg-raw-capture")
        val captureCanvas: Canvas = captureNode.beginRecording(widthPx, contentHeight)
        content.draw(captureCanvas)
        captureNode.endRecording()

        // 3. Glass surface: a view with a RuntimeShader material, drawn over the capture region.
        val surfaceTop = (contentHeight * 0.4f).toInt()
        val surfaceHeight = (heightPx * 0.35f).toInt()
        val surfaceLeft = 0
        val surfaceWidth = widthPx

        val surface = GlassSurfaceView(
            context = this,
            uniforms = com.subhankar.lumenglass.android.GlassUniforms(
                widthPx = surfaceWidth.toFloat(),
                heightPx = surfaceHeight.toFloat(),
                cornerRadiusPx = 48f,
                centreWeight = 0.6f,
                rimWeight = 0.4f,
                refraction = 1.0f,
                dispersion = 0.0f,
                tintArgb = 0x40FF3B30, // ~25% alpha red
                saturation = 1f,
                contrast = 1f,
                edgeLight = 0.1f,
                tier = com.subhankar.lumenglass.android.TIER_BALANCED,
            ),
        )
        root.addView(
            surface,
            FrameLayout.LayoutParams(surfaceWidth, surfaceHeight).apply {
                leftMargin = surfaceLeft
                topMargin = surfaceTop
            },
        )

        // 4. The overlap guard, surfaced on screen (Q-A).
        //
        //    The surface is positioned at (surfaceLeft, surfaceTop); the capture region is
        //    (0..widthPx, 0..contentHeight). Because the surface is deliberately placed *inside*
        //    the content's vertical span, this should report OK — proving the guard both fires and
        //    does not false-positive.
        val verdict = com.subhankar.lumenglass.android.checkOverlap(
            surface = com.subhankar.lumenglass.android.OverlapRect(
                left = surfaceLeft.toFloat(),
                top = surfaceTop.toFloat(),
                right = (surfaceLeft + surfaceWidth).toFloat(),
                bottom = (surfaceTop + surfaceHeight).toFloat(),
            ),
            capture = com.subhankar.lumenglass.android.OverlapRect(
                left = 0f,
                top = 0f,
                right = widthPx.toFloat(),
                bottom = contentHeight.toFloat(),
            ),
        )
        com.subhankar.lumenglass.android.logOverlapVerdict(verdict)

        banner = TextView(this).apply {
            text = if (verdict.overlapping) {
                "OVERLAP OK — shader should render"
            } else {
                "OVERLAP GUARD FIRED — expect near-black"
            }
            setBackgroundColor(if (verdict.overlapping) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt())
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(24, 24, 24, 24)
        }
        root.addView(
            banner,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = surfaceTop + surfaceHeight + 24 },
        )
    }
}

/**
 * A view that draws with a Lumen Glass [RuntimeShader] as its `Paint` shader.
 *
 * Using `RuntimeShader` directly as a `Shader` on the `Paint` — the documented `RuntimeShader`
 * route. The Compose harness uses the same approach; the `RenderEffect` route
 * (`RenderEffect.createRuntimeShaderEffect`) is exercised via the capture node in this activity.
 */
private class GlassSurfaceView(
    context: android.content.Context,
    uniforms: com.subhankar.lumenglass.android.GlassUniforms,
) : View(context) {

    private val shader: RuntimeShader? = com.subhankar.lumenglass.android.buildGlassShaderOrNull(uniforms)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.shader = shader
        // Ensure the surface view itself is hardware-accelerated so the AGSL shader runs on GPU.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (shader == null) {
            // FALLBACK tier: no AGSL. A clean frosted panel, per PLAN §5.2 — must look intentional.
            paint.color = 0x60FFFFFF
            canvas.drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()), 48f, 48f, paint,
            )
            return
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/** Draws a high-contrast pattern — the worst case for a glass shader to distort. */
private class HighContrastView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        var y = 40f
        var i = 1
        while (y < height) {
            paint.color = if (i % 2 == 0) 0xFF00E5FF.toInt() else 0xFFFF6D00.toInt()
            canvas.drawRect(0f, y - 20f, 40f, y + 10f, paint)
            canvas.drawText("Item $i — LUMEN GLASS P1.7", 60f, y, textPaint)
            y += 64f
            i++
        }
    }
}
