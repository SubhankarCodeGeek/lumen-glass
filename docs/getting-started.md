---
permalink: /docs/getting-started/
seo_title: "Getting Started: Liquid Glass in Jetpack Compose"
crumb: Getting started
description: >-
  Install Lumen Glass from JitPack and add liquid glass to Jetpack Compose with
  LumenSource and Modifier.lumenGlass. Quality tiers, API 33+ AGSL, fallback.
---

# Getting started with Lumen Glass

Lumen Glass draws a **liquid glass** material in Jetpack Compose: the content behind a
surface is refracted through an AGSL `RuntimeShader`, blurred with the platform's
hardware blur, and lit along the rim. This page takes you from an empty project to a
working glass card, and explains what happens on devices that cannot run the shader.

<img src="{{ '/assets/site/toric-610.webp' | relative_url }}"
     srcset="{{ '/assets/site/toric-320.webp' | relative_url }} 320w, {{ '/assets/site/toric-610.webp' | relative_url }} 610w"
     sizes="(max-width: 640px) 70vw, 305px" width="305" height="678" loading="lazy" decoding="async"
     alt="Phone capture of a rounded Lumen Glass card with the toric rim profile: the grid behind it is magnified and bent along the card's edge.">

## Requirements

| | Requirement |
|---|---|
| `minSdk` | **21**. The library installs and renders on any API 21+ app. |
| Full liquid glass (refraction, rim light, dispersion) | **Android 13 / API 33+** — the first release with AGSL `RuntimeShader`. |
| Below API 33 | A deliberate **frosted panel** fallback is drawn instead. Nothing crashes and nothing silently disappears. |
| UI toolkit | Jetpack Compose (`Modifier.lumenGlass`) or Android Views/XML (`LumenGlassView`). See [Views]({{ '/docs/VIEW.html' | relative_url }}) for the XML API. |
| Distribution | [JitPack](https://jitpack.io/#SubhankarCodeGeek/lumen-glass) |

## 1. Install from JitPack

Add the JitPack repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.SubhankarCodeGeek:lumen-glass:0.2.0")
}
```

The latest release tag is shown on the [JitPack page](https://jitpack.io/#SubhankarCodeGeek/lumen-glass)
and on [GitHub releases](https://github.com/SubhankarCodeGeek/lumen-glass/tags).

## 2. Understand the two pieces

A glass effect needs to know *what is behind it*. Lumen Glass splits that into two
composables sharing one state object:

1. **`LumenSource`** wraps the content that the glass should refract — a feed, a
   wallpaper, a map. It records that content into a graphics layer.
2. **`Modifier.lumenGlass`** goes on the element that should look like glass. It samples
   the recorded layer through the AGSL shader.

Both take the `LumenGlassState` returned by `rememberLumenGlassState()`.

> **They must overlap on screen.** The glass samples the source's pixels at its own
> position. If the glass sits outside the `LumenSource` region, there is nothing to
> sample and it renders near-black. Debug builds log a warning naming both rectangles
> when this happens.

## 3. Your first glass card

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.subhankar.lumenglass.compose.LumenSource
import com.subhankar.lumenglass.compose.lumenGlass
import com.subhankar.lumenglass.compose.rememberLumenGlassState

@Composable
fun GlassCard() {
    val glass = rememberLumenGlassState()

    Box(Modifier.fillMaxSize()) {
        // Everything inside LumenSource can be seen through the glass.
        LumenSource(state = glass, modifier = Modifier.fillMaxSize()) {
            MyBackgroundContent()
        }

        // The glass surface: sits on top of, and overlaps, the source.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(width = 320.dp, height = 180.dp)
                .lumenGlass(state = glass, cornerRadius = 28.dp)
        ) {
            Text("Liquid Glass", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
    }
}
```

`Modifier.lumenGlass(state)` with no configuration block already renders a reasonable
glass: every property has a default.

## 4. Tune the material

Pass a builder block to change the optics:

```kotlin
Modifier.lumenGlass(state = glass, cornerRadius = 28.dp) {
    refraction = 1.0f                      // 0 = no bending (plain frost)
    frost = 6.dp                           // platform blur radius, 0.dp = none
    tint = Color.White.copy(alpha = 0.12f) // tint needs its own alpha
    edgeLight = 0.1f                       // specular edge band, 0 = off
    rimProfile = RimProfile.TORIC          // TORIC, SMOOTH, DROPLET or LEGACY
    lens = LensModel.Blend(centre = 0.6f, rim = 0.4f)
}
```

`RimProfile` and `LensModel` live in `com.subhankar.lumenglass.core`. The full list of
properties and their defaults is in the
optical knobs reference in the
[README](https://github.com/SubhankarCodeGeek/lumen-glass#readme).

## 5. Quality tiers and what each device gets

`rememberLumenGlassState()` chooses a `QualityTier` from the device's capabilities:

| Device | Default tier | What renders |
|---|---|---|
| API 33+ | `BALANCED` | Refraction, rim lighting and platform blur at 0.75× capture scale. Dispersion and bezel are off. |
| Below API 33 | `FALLBACK` | A frosted panel; `LumenSource` simply draws its content. |

You can request a tier explicitly. A request is honoured only up to what the device can
do — asking for `HIGH` below API 33 still gives `FALLBACK`.

```kotlin
// Opt in to chromatic dispersion and the bezel (most expensive tier):
val glass = rememberLumenGlassState(quality = QualityTier.HIGH)

// Or save battery: reduced-resolution capture, no blur, tint + lens only.
val glass = rememberLumenGlassState(quality = QualityTier.LOW)
```

Check what the device actually got instead of guessing:

```kotlin
if (!glass.resolvedFeatures.useDispersion) {
    // e.g. hide a "dispersion" slider in your settings screen
}
```

`dispersion` and `bezel { … }` are only drawn on the `HIGH` tier, so set the tier if you
configure them.

## 6. Common problems

**The glass is black or very dark.** The glass surface does not overlap its
`LumenSource`. Make the source cover the area behind the glass (often
`Modifier.fillMaxSize()`), and make sure both use the same state object.

**Dispersion has no visible effect.** The default tier on API 33+ is `BALANCED`, which
skips dispersion. Use `rememberLumenGlassState(quality = QualityTier.HIGH)`.

**It looks like a plain frosted panel.** The device is below API 33, so the `FALLBACK` tier is active. That is the intended degradation.

## Next steps

- [Architecture]({{ '/docs/ARCHITECTURE.html' | relative_url }}) — how the capture
  substrate, AGSL shader and Compose API fit together.
- [Performance]({{ '/docs/PERF.html' | relative_url }}) — measured frame timing.
- [Clean-room record]({{ '/CLEANROOM.html' | relative_url }}) — where the shader maths comes from.
- [Source on GitHub](https://github.com/SubhankarCodeGeek/lumen-glass) — the
  interactive `app/` module lets you change every property live on a device.
