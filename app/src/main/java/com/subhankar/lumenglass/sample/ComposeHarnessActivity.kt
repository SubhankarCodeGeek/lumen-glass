package com.subhankar.lumenglass.sample

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhankar.lumenglass.android.LumenGlassDebug
import com.subhankar.lumenglass.compose.LumenSource
import com.subhankar.lumenglass.compose.lumenGlass
import com.subhankar.lumenglass.compose.rememberLumenGlassState
import com.subhankar.lumenglass.core.LensModel

/**
 * P4 harness — **the public API, used by a real consumer**.
 *
 * ## Why this screen was rewritten
 *
 * Through P1–P3 this harness reached into the library's `android/` layer (`GlassCapture`,
 * `applyGlassEffect`, `GlassBuilder` internals) to drive the shader. Those types were made public
 * for the harness's convenience, not because they were designed as API.
 *
 * P4 closes that: the harness now uses **only** `rememberLumenGlassState`, [LumenSource] and
 * [lumenGlass]. That is deliberate — if the public API cannot express what the harness needs, the
 * API is wrong, and this screen is where that shows up rather than in a consumer's app.
 *
 * Note the shape: [LumenSource] wraps the content in a lambda. That is required because recording
 * Compose content into a capture layer means re-emitting a draw pass, which only exists inside a
 * draw scope. See the KDoc on [LumenSource].
 */
class ComposeHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opt in to the library's debug instrumentation (overlap-guard logging).
        LumenGlassDebug.enabled = true
        setContent { ComposeHarness() }
    }
}

@Composable
private fun ComposeHarness() {
    var refraction by remember { mutableFloatStateOf(1.2f) }
    var dispersion by remember { mutableFloatStateOf(0.0f) }
    var centreWeight by remember { mutableFloatStateOf(0.6f) }
    var rimWeight by remember { mutableFloatStateOf(0.4f) }
    var tintAlpha by remember { mutableFloatStateOf(0.15f) }

    // Shifts the glass surface off the captured region to exercise the overlap guard. 0 = aligned.
    var breakOverlapPx by remember { mutableFloatStateOf(0f) }

    // The ONLY library entry point this screen needs for state.
    val glass = rememberLumenGlassState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Everything behind the glass is wrapped in LumenSource so it is recorded into the
            // capture. The glass surface below refracts this.
            LumenSource(glass, Modifier.weight(1f)) {
                HighContrastScrollingContent(Modifier.fillMaxSize())
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(horizontal = 12.dp)
                    .padding(start = with(androidx.compose.ui.platform.LocalDensity.current) {
                        breakOverlapPx.toDp()
                    })
                    // The public modifier: no knowledge of shaders, captures or uniforms.
                    .lumenGlass(glass) {
                        lens = LensModel.Blend(centre = centreWeight, rim = rimWeight)
                        this.refraction = refraction
                        this.dispersion = dispersion
                        tint = Color(0xFFFF3B30).copy(alpha = tintAlpha)
                        frost = 6.dp
                        edgeLight = 0.1f
                    },
            )

            // Report what the library ACTUALLY chose, not what we hoped — the detectability
            // contract from P3.5, now a public property.
            Text(
                text = "tier ${glass.tier.name} · shader=${glass.canRunShader} · " +
                    "blur=${glass.resolvedFeatures.useBlur} " +
                    "dispersion=${glass.resolvedFeatures.useDispersion} " +
                    "capture=${glass.resolvedFeatures.captureScale}",
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            SliderRow("refraction", refraction, 0f..3f) { refraction = it }
            SliderRow("dispersion", dispersion, 0f..1f) { dispersion = it }
            SliderRow("centreWeight", centreWeight, 0f..1f) { centreWeight = it }
            SliderRow("rimWeight", rimWeight, 0f..1f) { rimWeight = it }
            SliderRow("tintAlpha", tintAlpha, 0f..1f) { tintAlpha = it }
            // Range exceeds the surface width so the slider can drive overlap to zero and prove the
            // guard fires.
            SliderRow("breakOverlap", breakOverlapPx, 0f..2000f) { breakOverlapPx = it }
        }
    }
}

/** High-contrast scrolling content — the worst case for a glass shader. */
@Composable
private fun HighContrastScrollingContent(modifier: Modifier) {
    val items = remember { (1..60).map { "Item $it — LUMEN GLASS P4" } }
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
