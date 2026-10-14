package com.subhankar.lumenglass.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhankar.lumenglass.android.LumenGlassDebug
import com.subhankar.lumenglass.compose.LumenSource
import com.subhankar.lumenglass.compose.lumenGlass
import com.subhankar.lumenglass.compose.rememberLumenGlassState
import com.subhankar.lumenglass.core.LensModel

/**
 * P4 Harness — Comprehensive interactive showcase of the LumenGlass public API.
 *
 * Demonstrates real-time AGSL refraction over rich gradient backdrops across
 * multiple distinct UI usecases:
 * 1. **Floating Card**: An overlay dialog/card with text and button elements inside.
 * 2. **Bottom Dock**: A glass navigation bar with icons.
 * 3. **Split Surface**: Half-screen comparison showing raw vs refracted content.
 */
class ComposeHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LumenGlassDebug.enabled = true
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF101216),
            ) {
                ComposeHarness()
            }
        }
    }
}

private enum class GlassUsecase(val label: String) {
    FLOATING_CARD("Floating Card"),
    BOTTOM_DOCK("Bottom Dock"),
    SPLIT_SHEET("Split View"),
}

private data class TintPreset(val name: String, val color: Color)

private val TINT_PRESETS = listOf(
    TintPreset("Clear", Color.White.copy(alpha = 0.08f)),
    TintPreset("Sunset Rose", Color(0xFFFF3B30).copy(alpha = 0.20f)),
    TintPreset("Ice Cyan", Color(0xFF00E5FF).copy(alpha = 0.18f)),
    TintPreset("Golden Amber", Color(0xFFFFB300).copy(alpha = 0.22f)),
    TintPreset("Obsidian", Color(0xFF212121).copy(alpha = 0.35f)),
)

@Composable
private fun ComposeHarness() {
    var selectedUsecase by remember { mutableStateOf(GlassUsecase.FLOATING_CARD) }
    var selectedTint by remember { mutableStateOf(TINT_PRESETS[1]) }

    var refraction by remember { mutableFloatStateOf(1.2f) }
    var dispersion by remember { mutableFloatStateOf(0.15f) }
    var centreWeight by remember { mutableFloatStateOf(0.6f) }
    var rimWeight by remember { mutableFloatStateOf(0.4f) }
    var frostDp by remember { mutableFloatStateOf(6f) }

    val glass = rememberLumenGlassState()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "LumenGlass Studio",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Tier: ${glass.tier.name} · AGSL: ${glass.canRunShader}",
                    color = Color(0xFF81C784),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Status chip
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF263238))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "Clean-Room GPU",
                    color = Color(0xFF80DEEA),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Use case selector chips
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassUsecase.entries.forEach { usecase ->
                FilterChip(
                    selected = selectedUsecase == usecase,
                    onClick = { selectedUsecase = usecase },
                    label = { Text(usecase.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E5FF),
                        selectedLabelColor = Color.Black,
                        containerColor = Color(0xFF1E2630),
                        labelColor = Color(0xFFB0BEC5),
                    ),
                )
            }
        }

        // Viewport: Source background + Glass surface
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            // BACKDROP: Gradient list wrapped in LumenSource
            LumenSource(glass, Modifier.fillMaxSize()) {
                GradientBackdrop(Modifier.fillMaxSize())
            }

            // GLASS OVERLAY: Adapts to selected use case
            when (selectedUsecase) {
                GlassUsecase.FLOATING_CARD -> {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.85f)
                            .height(200.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .lumenGlass(glass, cornerRadius = 24.dp) {
                                lens = LensModel.Blend(centre = centreWeight, rim = rimWeight)
                                this.refraction = refraction
                                this.dispersion = dispersion
                                this.tint = selectedTint.color
                                this.frost = frostDp.dp
                                this.edgeLight = 0.2f
                            }
                            .padding(20.dp),
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Optical Shield",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E5FF)),
                                )
                            }
                            Text(
                                text = "Real-time clean-room squircle refraction shader with chromatic dispersion.",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.25f),
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Engage", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                GlassUsecase.BOTTOM_DOCK -> {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.92f)
                            .padding(bottom = 16.dp)
                            .height(72.dp)
                            .clip(RoundedCornerShape(36.dp))
                            .lumenGlass(glass, cornerRadius = 36.dp) {
                                lens = LensModel.Blend(centre = centreWeight, rim = rimWeight)
                                this.refraction = refraction
                                this.dispersion = dispersion
                                this.tint = selectedTint.color
                                this.frost = frostDp.dp
                                this.edgeLight = 0.25f
                            }
                            .padding(horizontal = 24.dp),
                    ) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DockItem("✦", "Home", true)
                            DockItem("◈", "Optics", false)
                            DockItem("◉", "Tiers", false)
                            DockItem("⚙", "Config", false)
                        }
                    }
                }

                GlassUsecase.SPLIT_SHEET -> {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxWidth(0.55f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                            .lumenGlass(glass, cornerRadius = 24.dp) {
                                lens = LensModel.Blend(centre = centreWeight, rim = rimWeight)
                                this.refraction = refraction
                                this.dispersion = dispersion
                                this.tint = selectedTint.color
                                this.frost = frostDp.dp
                                this.edgeLight = 0.15f
                            }
                            .padding(16.dp),
                    ) {
                        Column {
                            Text(
                                text = "Split Comparison",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "← Raw Content | Refracted Glass →",
                                color = Color(0xFF00E5FF),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        // Tint Selector
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TINT_PRESETS.forEach { preset ->
                FilterChip(
                    selected = selectedTint == preset,
                    onClick = { selectedTint = preset },
                    label = { Text(preset.name, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFFF4081),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF222B36),
                        labelColor = Color(0xFFCFD8DC),
                    ),
                )
            }
        }

        // Dynamic parameter sliders panel
        Column(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            SliderRow("Refraction", refraction, 0f..2.5f) { refraction = it }
            SliderRow("Dispersion", dispersion, 0f..0.5f) { dispersion = it }
            SliderRow("Frost (Blur)", frostDp, 0f..24f) { frostDp = it }
            SliderRow("Centre Weight", centreWeight, 0f..1f) { centreWeight = it }
            SliderRow("Rim Weight", rimWeight, 0f..1f) { rimWeight = it }
        }
    }
}

@Composable
private fun DockItem(icon: String, title: String, selected: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = icon,
            color = if (selected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = title,
            color = if (selected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label: ${"%.2f".format(value)}",
            color = Color(0xFFB0BEC5),
            fontSize = 11.sp,
            modifier = Modifier.width(130.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF00E5FF),
                activeTrackColor = Color(0xFF00E5FF),
                inactiveTrackColor = Color(0xFF37474F),
            ),
        )
    }
}
