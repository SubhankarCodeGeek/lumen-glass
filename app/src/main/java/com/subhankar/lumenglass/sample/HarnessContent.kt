package com.subhankar.lumenglass.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The harness's backdrop content: a **high-contrast gradient list**.
 *
 * ## Why a gradient, and why these colours
 *
 * Verifying a glass effect needs content that makes *every* optical behaviour legible. A flat
 * background hides all of them — a lens over a solid colour is invisible, which is exactly how a
 * broken lens passes unnoticed.
 *
 * What each part is for:
 *
 * | Feature | Makes visible |
 * |---|---|
 * | Saturated gradient bands | **Refraction** — a straight edge visibly bends |
 * | Fine pinstripes on the bands | **Small displacements** — a flat field hides sub-pixel bends |
 * | Hard black/white edges | **Frost/blur radius** — the edge softens measurably |
 * | Cyan/magenta/yellow text | **Chromatic dispersion** — the channel fringing shows as colour separation |
 * | Alternating row heights | **Odd/even sampling artefacts** |
 *
 * The palette is deliberately saturated and mutually distinct so a screenshot can be eyeballed for
 * correctness, and so pixel assertions can pick unambiguous probe colours.
 */
@Composable
internal fun GradientBackdrop(
    modifier: Modifier = Modifier,
    itemCount: Int = 40,
) {
    val items = remember(itemCount) { (1..itemCount).toList() }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items) { index ->
            GradientBand(index)
        }
        item {
            // A solid, unambiguous block at the end: if the glass is sampling empty space, this is
            // where a flat patch of pure black shows up most obviously.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color.Black),
            )
        }
    }
}

/**
 * One gradient band, with content chosen to expose a specific optical behaviour.
 *
 * Alternates through a small palette so consecutive rows have very different colours — which makes
 * a *mis-sampled* capture obvious (wrong row's colour appearing) as well as a smeared one.
 */
@Composable
private fun GradientBand(index: Int) {
    // Six saturated, mutually distinct hues, cycle through them.
    val hues = HARNESS_HUES
    val from = hues[index % hues.size]
    val to = hues[(index + 2) % hues.size]

    Box(
        Modifier
            .fillMaxWidth()
            .height(if (index % 3 == 0) 96.dp else 72.dp)
            .background(
                Brush.horizontalGradient(listOf(from, to)),
            ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                // White on a saturated hue: a hard edge for blur/edge-light to act on.
                Text(
                    text = "Band ${index + 1}",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                // Cyan and magenta are the two channels dispersion separates most — so this line
                // shows colour fringing directly.
                Text(
                    text = "◉ dispersion probe",
                    color = Color(0xFF00FFFF),
                    fontSize = 11.sp,
                )
                Text(
                    text = "◉ rim probe",
                    color = Color(0xFFFF00FF),
                    fontSize = 11.sp,
                )
            }

            // A white disc: a curved hard edge, which reads refraction more clearly than a straight
            // one because the bend direction changes around its circumference.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }

        // Fine pinstripes along the bottom edge of each band. These make SUB-PIXEL displacement
        // legible — a smooth gradient would hide the small bends that a weak lens produces.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(3) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

/** Saturated, mutually distinct hues used by [GradientBand]. */
private val HARNESS_HUES = listOf(
    Color(0xFF1B5E20), // deep green
    Color(0xFF0D47A1), // deep blue
    Color(0xFF4A148C), // deep purple
    Color(0xFFB71C1C), // deep red
    Color(0xFFE65100), // deep orange
    Color(0xFF006064), // deep teal
)
