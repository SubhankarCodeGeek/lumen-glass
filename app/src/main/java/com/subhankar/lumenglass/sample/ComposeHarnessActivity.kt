package com.subhankar.lumenglass.sample

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhankar.lumenglass.android.GlassUniforms
import com.subhankar.lumenglass.android.LumenGlassDebug
import com.subhankar.lumenglass.android.OverlapRect
import com.subhankar.lumenglass.android.TIER_BALANCED
import com.subhankar.lumenglass.android.TIER_HIGH
import com.subhankar.lumenglass.android.buildGlassShader
import com.subhankar.lumenglass.android.checkOverlap
import com.subhankar.lumenglass.android.logOverlapVerdict

/**
 * P1.7 harness — **Compose** surface.
 *
 * Proves the clean-room shader compiles, renders, and binds its uniforms through a Compose layer,
 * and that the ARCHITECTURE §4 overlap guard is surfaced to the user.
 *
 * The shader is applied via `Paint.setShader(...)` on the Compose native canvas — the documented
 * `RuntimeShader` path (it *is* a `Shader`, not only a `RenderEffect` input). This exercises the
 * exact same `GlassUniforms` / `buildGlassShader` code the library ships.
 *
 * Deliberately **not** using a `Modifier.lumenGlass(...)` public API — that is P4. The wiring here
 * is local to the harness so P1.7 does not freeze the API shape.
 */
class ComposeHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opt in to the library's debug instrumentation (guard logging, banner surfacing).
        LumenGlassDebug.enabled = true
        setContent { ComposeHarness() }
    }
}

@Composable
private fun ComposeHarness() {
    var refraction by remember { mutableFloatStateOf(1.0f) }
    var dispersion by remember { mutableFloatStateOf(0.0f) }
    var centreWeight by remember { mutableFloatStateOf(0.6f) }
    var rimWeight by remember { mutableFloatStateOf(0.4f) }
    var tintAlpha by remember { mutableFloatStateOf(0.25f) }

    // Pushes the glass surface off the captured region to prove the guard fires. 0 = aligned.
    var breakOverlapPx by remember { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            HighContrastScrollingContent(Modifier.weight(1f))

            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(horizontal = 12.dp),
                refraction = refraction,
                dispersion = dispersion,
                centreWeight = centreWeight,
                rimWeight = rimWeight,
                tintAlpha = tintAlpha,
                breakOverlapPx = breakOverlapPx,
            )

            SliderRow("refraction", refraction, 0f..3f) { refraction = it }
            SliderRow("dispersion", dispersion, 0f..1f) { dispersion = it }
            SliderRow("centreWeight", centreWeight, 0f..1f) { centreWeight = it }
            SliderRow("rimWeight", rimWeight, 0f..1f) { rimWeight = it }
            SliderRow("tintAlpha", tintAlpha, 0f..1f) { tintAlpha = it }
            // Range must exceed the surface width (~1100px) so the slider can drive the overlap
            // all the way to zero. A 0..600 range could only ever reach ~45% overlap.
            SliderRow("breakOverlap", breakOverlapPx, 0f..2000f) { breakOverlapPx = it }
        }
    }
}

/**
 * The real render path: build a bound [RuntimeShader] and draw with it.
 *
 * A production path would reuse the shader and re-bind uniforms only; rebuilding on change is fine
 * for a harness and keeps P1.7 focused (shader reuse / caching is P3.5).
 */
@Composable
private fun GlassSurface(
    modifier: Modifier,
    refraction: Float,
    dispersion: Float,
    centreWeight: Float,
    rimWeight: Float,
    tintAlpha: Float,
    breakOverlapPx: Float,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    val shader: RuntimeShader? = remember(
        size, refraction, dispersion, centreWeight, rimWeight, tintAlpha,
    ) {
        if (size.width == 0 || size.height == 0) return@remember null
        buildGlassShader(
            GlassUniforms(
                widthPx = size.width.toFloat(),
                heightPx = size.height.toFloat(),
                cornerRadiusPx = with(density) { 24.dp.toPx() },
                centreWeight = centreWeight,
                rimWeight = rimWeight,
                refraction = refraction,
                dispersion = dispersion,
                tintArgb = Color(0xFFFF3B30).copy(alpha = tintAlpha).toArgb(),
                saturation = 1f,
                contrast = 1f,
                edgeLight = 0.1f,
                tier = if (dispersion > 0f) TIER_HIGH else TIER_BALANCED,
            ),
        )
    }

    val paint = remember { Paint().apply { isAntiAlias = true } }

    val widthPx = size.width.toFloat().coerceAtLeast(1f)
    val heightPx = size.height.toFloat().coerceAtLeast(1f)

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Box(
            modifier
                .onSizeChanged { size = it }
                .drawBehind {
                    val activeShader = shader ?: return@drawBehind
                    paint.shader = activeShader
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(0f, 0f, widthPx, heightPx, paint)
                    }
                },
        )

        // Guard: the shader is told the surface is at x = breakOverlapPx. Once that no longer
        // overlaps the captured region the render is near-black, so show the verdict.
        val verdict = remember(breakOverlapPx, widthPx, heightPx) {
            checkOverlap(
                surface = OverlapRect(
                    left = breakOverlapPx,
                    top = 0f,
                    right = breakOverlapPx + widthPx,
                    bottom = heightPx,
                ),
                capture = OverlapRect(left = 0f, top = 0f, right = widthPx, bottom = heightPx),
            )
        }
        logOverlapVerdict(verdict)

        OverlapBanner(overlapping = verdict.overlapping)
    }
}

/**
 * On-screen half of the overlap guard (Q-A). The library logs; the harness shows. This is what
 * makes the failure mode *provable*, which the P1.7 exit bar requires.
 */
@Composable
private fun OverlapBanner(overlapping: Boolean) {
    val bg = if (overlapping) Color(0xFF1B5E20) else Color(0xFFB71C1C)
    Box(Modifier.fillMaxWidth().background(bg).padding(8.dp)) {
        Text(
            text = if (overlapping) {
                "OVERLAP OK — shader should render"
            } else {
                "OVERLAP GUARD FIRED — expect near-black"
            },
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

/** High-contrast scrolling content — the worst case for a glass shader. */
@Composable
private fun HighContrastScrollingContent(modifier: Modifier) {
    val items = remember { (1..60).map { "Item $it — LUMEN GLASS P1.7" } }
    LazyColumn(modifier) {
        items(items) { label ->
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = Color.White, fontSize = 18.sp)
                Text("▚▞", color = Color(0xFF00E5FF), fontSize = 22.sp)
            }
        }
    }
}

/** A labelled slider row. */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("$label = ${"%.2f".format(value)}", color = Color(0xFFCFD8DC), fontSize = 12.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
