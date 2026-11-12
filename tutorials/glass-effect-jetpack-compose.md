---
permalink: /tutorials/glass-effect-jetpack-compose/
seo_title: "Jetpack Compose Glass Effect Tutorial"
crumb: "Tutorial: glass bottom bar"
description: >-
  Step-by-step Jetpack Compose tutorial: build a liquid glass bottom navigation
  bar over a scrolling list with an AGSL refraction shader, plus fallbacks.
---

# Glass effect in Jetpack Compose: build a liquid glass bottom bar

This tutorial builds one of the most common glass UI patterns, a **floating bottom
navigation bar that refracts the list scrolling underneath it**, in Jetpack Compose.
The glass is drawn by Lumen Glass: the content behind the bar is bent through an
AGSL `RuntimeShader`, softened with the platform blur and lit along its rim.

<img src="{{ '/assets/site/liquid-dock-610.webp' | relative_url }}"
     srcset="{{ '/assets/site/liquid-dock-320.webp' | relative_url }} 320w, {{ '/assets/site/liquid-dock-610.webp' | relative_url }} 610w"
     sizes="(max-width: 640px) 70vw, 305px" width="305" height="678" loading="lazy" decoding="async"
     alt="Phone capture of a floating bottom navigation pill made of glass, refracting the app content behind it.">

By the end you will have a screen that:

- scrolls a list *behind* a pill-shaped glass dock,
- keeps the last list item reachable above the dock and the system navigation bar,
- keeps its labels readable over bright and dark content,
- degrades to a frosted panel on devices older than Android 13.

**Before you start:** add the dependency as shown in
[Getting started]({{ '/docs/getting-started/' | relative_url }}). This page uses version
`0.2.0`. Full liquid glass needs Android 13 (API 33) or newer; the library itself installs
on API 21+.

## How a glass surface works in Compose

A glass surface has to know what is behind it, and Compose does not give a composable
access to the pixels of its siblings. Lumen Glass solves this with two pieces that share
one state object:

| Piece | Role |
|---|---|
| `rememberLumenGlassState()` | Picks a quality tier for the device and connects the other two pieces. |
| `LumenSource(state) { … }` | Wraps the content that should show through the glass and records it into a graphics layer each frame. |
| `Modifier.lumenGlass(state) { … }` | Turns a composable into glass by sampling that recorded layer through the shader at the composable's on-screen position. |

The one rule to remember: **the glass must sit on top of, and overlap, its
`LumenSource`.** If it does not, there is nothing behind it to sample and it renders
near-black. The usual layout is therefore a `Box` with the source filling it and the
glass aligned on top.

## Step 1: put the scrolling content inside `LumenSource`

```kotlin
@Composable
fun GlassDockScreen() {
    val glass = rememberLumenGlassState()

    Box(Modifier.fillMaxSize()) {
        LumenSource(state = glass, modifier = Modifier.fillMaxSize()) {
            FeedList(Modifier.fillMaxSize())
        }

        // Step 2 adds the dock here, on top of the source.
    }
}
```

`LumenSource` draws its content normally, so the list looks and scrolls exactly as it
would without it. Make the source cover the whole area the dock can sit over:
`Modifier.fillMaxSize()` is the simplest way.

## Step 2: add the glass dock

The dock is an ordinary `Box` aligned to the bottom of the parent. The glass modifier
goes on it, after the clip:

```kotlin
Box(
    Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(bottom = 16.dp)
        .fillMaxWidth(0.92f)
        .height(72.dp)
        .clip(RoundedCornerShape(36.dp))
        .lumenGlass(state = glass, cornerRadius = 36.dp)
        .padding(horizontal = 24.dp),
) {
    DockItems()
}
```

Three details matter here:

1. **`cornerRadius` matches the clip.** The shader uses the corner radius to shape the
   rim: the bending, the edge highlight and the bezel all follow the rounded outline.
   Pass the same value you clip with, here half the height for a full pill.
2. **Clip before `lumenGlass`.** The clip gives the dock its shape and keeps everything
   it draws inside that shape. The sample app in the repository uses the same order.
3. **Insets go outside the glass.** `navigationBarsPadding()` and the bottom padding are
   applied before the size, so the pill floats above the gesture bar rather than
   stretching behind it.

`Modifier.lumenGlass(state)` with no configuration block already renders a usable glass.
The next step tunes it for a navigation bar.

## Step 3: tune the material for a navigation bar

A dock is small and sits over moving content, so it reads best with a clear rim and a
little more frost than a large card:

```kotlin
.lumenGlass(state = glass, cornerRadius = 36.dp) {
    rimProfile = RimProfile.TORIC              // circular rim: magnifies along the edge
    lens = LensModel.Blend(centre = 0.2f, rim = 0.6f)
    refraction = 1.0f                          // overall bending; 0 = flat frost
    frost = 8.dp                               // platform blur radius
    tint = Color.Black.copy(alpha = 0.18f)     // see step 5
    edgeLight = 0.35f                          // specular band along the rim
}
```

What each setting does:

| Setting | Effect on the dock | Default |
|---|---|---|
| `rimProfile` | Shape of the rim's cross-section. `TORIC` bends content inward along the edge; `SMOOTH` is a softer bevel; `DROPLET` a fluid, two-zone edge; `LEGACY` the original squircle field. | `TORIC` |
| `lens` | Where the bending happens. `Blend(centre, rim)` mixes a centre lens with rim refraction; weighting the rim keeps the icons in the middle undistorted. | `Blend(0.6f, 0.4f)` |
| `refraction` | Strength of the displacement. | `1.0f` |
| `frost` | Blur applied to what is behind the glass. `0.dp` turns it off. | `6.dp` |
| `tint` | Colour laid over the glass. It needs its own alpha. | none |
| `edgeLight` | Strength of the bright band along the edge. `0f` turns it off. | `0.1f` |

`RimProfile` and `LensModel` are imported from `com.subhankar.lumenglass.core`. The
easiest way to choose values is the sample app in the repository's `app/` module: its
"Bottom Dock" mode shows this exact layout with a slider for every property, running on
your own device.

## Step 4: keep the last item reachable

The list now scrolls under the dock, which is the point, but its last item can end up
permanently hidden behind it. Give the list bottom content padding equal to the space the
dock takes:

```kotlin
@Composable
fun FeedList(modifier: Modifier = Modifier) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = bottomInset + 72.dp + 16.dp + 16.dp, // inset + dock + gap + breathing room
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(40) { index -> FeedCard(index) }
    }
}
```

Use `contentPadding`, not `Modifier.padding`: padding on the modifier would shrink the
list so nothing scrolls behind the dock, and there would be no content for the glass to
refract.

## Step 5: keep the labels readable

Glass takes on the colours of whatever passes underneath it. White labels that look great
over a dark photo can disappear over a white card. Two settings help:

- **`tint`** adds a constant layer under your labels. A dark tint such as
  `Color.Black.copy(alpha = 0.18f)` keeps white labels readable over light content; use a
  light tint with dark labels for a light theme.
- **`frost`** smooths out busy detail behind the text, so edges in the content do not
  compete with the letter shapes.

Check the dock over the brightest and the busiest content your screen can show, and
raise the tint alpha until the labels pass your contrast check there. Glass is a
decoration; the labels are the interface.

## Step 6: what other devices get

`rememberLumenGlassState()` picks a quality tier from the device:

| Device | Default tier | What the dock looks like |
|---|---|---|
| Android 13+ (API 33+) | `BALANCED` | Refraction, rim light and blur, with the backdrop captured at 0.75× resolution. |
| Below API 33 | `FALLBACK` | A plain frosted panel. The list behind it renders normally. |

On older devices the frosted panel is drawn by the library itself, so the dock still has
a visible surface and the layout is unchanged; you do not need a second code path. If
your design depends on something only the full effect provides, read what the device
actually got:

```kotlin
if (!glass.canRunShader) {
    // e.g. use a stronger tint or an opaque bar on older devices
}
```

To trade quality for battery on a long-lived screen, request a cheaper tier:
`rememberLumenGlassState(quality = QualityTier.LOW)` captures at half resolution and skips
the blur. `QualityTier.HIGH` enables chromatic `dispersion` and the `bezel { … }` inner
shadow, which the default tier does not draw. Measured frame timings for each tier are on
the [Performance]({{ '/docs/PERF.html' | relative_url }}) page.

## Limitations to know about (version 0.2.0)

- **One glass surface per state.** A `LumenGlassState` tracks the position of a single
  glass surface. For a dock plus a glass top bar, do not apply both modifiers with the
  same state.
- **Overlap is required.** A glass surface outside its `LumenSource` renders near-black.
  Debug builds log a warning naming both rectangles when this happens.
- **The source is recorded every frame it draws.** Keep the source to the content that
  actually shows through the glass; the Performance page explains the cost.

## The complete screen

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhankar.lumenglass.compose.LumenSource
import com.subhankar.lumenglass.compose.lumenGlass
import com.subhankar.lumenglass.compose.rememberLumenGlassState
import com.subhankar.lumenglass.core.LensModel
import com.subhankar.lumenglass.core.RimProfile

private val DockHeight = 72.dp
private val DockGap = 16.dp

@Composable
fun GlassDockScreen() {
    val glass = rememberLumenGlassState()
    var selected by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        LumenSource(state = glass, modifier = Modifier.fillMaxSize()) {
            FeedList(Modifier.fillMaxSize())
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = DockGap)
                .fillMaxWidth(0.92f)
                .height(DockHeight)
                .clip(RoundedCornerShape(DockHeight / 2))
                .lumenGlass(state = glass, cornerRadius = DockHeight / 2) {
                    rimProfile = RimProfile.TORIC
                    lens = LensModel.Blend(centre = 0.2f, rim = 0.6f)
                    refraction = 1.0f
                    frost = 8.dp
                    tint = Color.Black.copy(alpha = 0.18f)
                    edgeLight = 0.35f
                }
                .padding(horizontal = 24.dp),
        ) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("Home", "Search", "Library", "Profile").forEachIndexed { index, label ->
                    DockItem(label, selected = index == selected) { selected = index }
                }
            }
        }
    }
}

@Composable
private fun DockItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun FeedList(modifier: Modifier = Modifier) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = bottomInset + DockHeight + DockGap + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(40) { index -> FeedCard(index) }
    }
}

@Composable
private fun FeedCard(index: Int) {
    val hue = (index * 37 % 360).toFloat()
    Column(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color.hsv(hue, 0.6f, 0.9f), Color.hsv((hue + 60f) % 360f, 0.7f, 0.5f)),
                ),
            )
            .padding(16.dp),
    ) {
        Text("Item ${index + 1}", color = Color.White, fontSize = 18.sp)
    }
}
```

The colourful gradient cards are only there so the refraction is easy to see while you
experiment; any content works as the backdrop.

## Next steps

- [Getting started]({{ '/docs/getting-started/' | relative_url }}): installation, the
  floating glass card example and troubleshooting.
- [Architecture]({{ '/docs/ARCHITECTURE.html' | relative_url }}): how the capture, the AGSL
  shader and the Compose API fit together.
- [Performance]({{ '/docs/PERF.html' | relative_url }}): frame timing per quality tier.
- [Views & XML]({{ '/docs/VIEW.html' | relative_url }}): the same glass for apps that are not
  on Compose.
- [Source on GitHub](https://github.com/SubhankarCodeGeek/lumen-glass): the sample app's
  "Bottom Dock" mode is this screen with live controls.
