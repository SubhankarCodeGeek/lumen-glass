# Lumen Glass

A physically accurate, clean-room **Liquid Glass rendering library for Android & Jetpack Compose**.

Lumen Glass brings Apple's authentic **Liquid Glass material** to Android with real-time AGSL refraction, spherical toric edge magnification, full-perimeter highlight stroke with directional sheen, chromatic dispersion, hardware blur, and adaptive 60fps performance tiers — built on a substrate **we own end-to-end**.

<p align="center">
  <img src="assets/device_screen_live.png" width="380" alt="LumenGlass Studio on Device" />
</p>

---

## Highlights & Optical Physics

| Feature | Lumen Glass | Conventional Glass Wrappers |
|---|---|---|
| **Edge Magnification** | **Spherical Toric Meniscus** ($1.0 - \sqrt{1 - u^2}$) with physical zoom | Flat or linear step; no edge zoom |
| **Rim Profiles** | **3 Profiles**: Toric Meniscus, Smooth Hermite, Liquid Droplet | Single fixed edge falloff |
| **Edge Lighting** | **Dual-Lobe Directional Sheen** + 360° perimeter highlight stroke | Basic uniform stroke or blacked-out sides |
| **Lens Model** | **Tunable Blend**: Centre-weighted + Rim-weighted | Rim-only (middle remains flat) |
| **Chromatic Dispersion** | Calibrated per-channel refractive index offset (R/G/B) | Single pass or none |
| **Substrate Ownership** | **Zero third-party dependencies** (Android `RenderNode` capture) | Wrapper over `backdrop` / `liquid` |
| **Licensing** | **100% Clean-Room** Apache-2.0 (independently derived) | Often unlicensed Shadertoy ports |
| **Performance Tiers** | `HIGH`, `BALANCED`, `LOW`, `FALLBACK` (API < 33 frosted panel) | Uncapped fragment overhead |

---

## Edge Bending Profiles

Lumen Glass provides three distinct rim bending profiles to match different design languages:

1. **Pattern A: Toric Meniscus (`RimProfile.TORIC`)** *(Default)*
   - Physically derived spherical toric meniscus lens: $d(u) = 1.0 - \sqrt{1.0 - u^2}$.
   - Displaces content along boundary normals inward, magnifying background elements along the rim just like genuine optical glass and Apple Control Center sheets.
2. **Pattern B: Smooth Hermite (`RimProfile.SMOOTH`)**
   - Continuous $G^2$-curvature fillet combining a soft linear ramp with quadratic acceleration: $0.30u + 0.70u^2$.
   - Delivers a velvety, progressive magnification without abrupt slope jumps.
3. **Pattern C: Liquid Droplet (`RimProfile.DROPLET`)**
   - Bipolar fluid droplet meniscus combining an outward environmental refraction bulge ($\sin(\pi u)$) with perimeter inward zoom.
   - Recreates organic, fluid-like meniscus refraction seen in macOS liquid sheets.

---

## Installation

### Gradle (JitPack)

Add the JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.Subhankar:LumenGlass:0.1.0")
}
```

---

## Quick Start (Jetpack Compose)

```kotlin
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.subhankar.lumenglass.compose.LumenSource
import com.subhankar.lumenglass.compose.lumenGlass
import com.subhankar.lumenglass.compose.rememberLumenGlassState
import com.subhankar.lumenglass.core.LensModel
import com.subhankar.lumenglass.core.RimProfile

@Composable
fun LiquidGlassCardDemo() {
    val glassState = rememberLumenGlassState()

    Box(Modifier.fillMaxSize()) {
        // 1. Mark background content to be captured into the glass substrate
        LumenSource(
            state = glassState,
            modifier = Modifier.fillMaxSize()
        ) {
            // Your rich background (scrolling lists, images, gradients)
            BackgroundContent()
        }

        // 2. Render your glass element over the recorded source
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(320.dp, 180.dp)
                .lumenGlass(
                    state = glassState,
                    cornerRadius = 28.dp, // Matches glass rim width to corner radius
                ) {
                    refraction = 1.0f
                    dispersion = 0.08f
                    frost = 6.dp
                    highlightStroke = 0.28f
                    rimProfile = RimProfile.TORIC
                    tint = Color.White.copy(alpha = 0.12f)
                    lens = LensModel.Blend(centre = 0.3f, rim = 0.7f)
                }
        ) {
            Text(
                text = "Liquid Glass",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
```

---

## Tunable Optical Knobs

| Property | Type | Default | Description |
|---|---|---|---|
| `refraction` | `Float` | `1.0f` | Refractive strength ($0.0$ to $2.0$). |
| `dispersion` | `Float` | `0.06f` | Chromatic dispersion aberration (RGB wavelength splitting). |
| `frost` | `Dp` | `6.dp` | Hardware-accelerated separable Gaussian blur radius. |
| `highlightStroke` | `Float` | `0.28f` | Ambient baseline for the 360° perimeter highlight stroke. |
| `rimProfile` | `RimProfile` | `TORIC` | Refraction geometry: `TORIC`, `SMOOTH`, `DROPLET`, or `LEGACY`. |
| `tint` | `Color` | `Color.Transparent` | Translucent glass surface tint. |
| `edgeLight` | `Float` | `0.10f` | Directional specular sheen intensity on top bevel. |
| `lens` | `LensModel` | `Blend(0.4f, 0.6f)` | Lens distribution: `Centre(w)`, `Rim(w)`, or `Blend(c, r)`. |
| `bezel` | `Bezel?` | `Bezel.soft(12.dp)` | Optional inner shadow boundary contour. |

---

## Adaptive Quality Tiers

Lumen Glass features an internal capability probe that adapts rendering based on device GPU performance:

- **HIGH**: Full multi-sample refraction, chromatic dispersion, hardware blur, directional sheen, inner bezel.
- **BALANCED**: Platform separable blur, directional sheen, calibrated refraction, optional dispersion.
- **LOW**: Reduced-res capture (`0.5x` scale), tint and single-tap lens, dispersion bypassed.
- **FALLBACK**: API < 33 or devices without AGSL support degrade gracefully to a clean frosted panel with intentional styling.

---

## Architecture & Clean-Room Guarantee

```
LumenGlass/
├── library/
│   ├── core/         # Pure platform-agnostic optical model (zero Android/androidx imports)
│   ├── android/      # RuntimeShader, RenderEffect, RenderNode capture substrate
│   └── compose/      # Public Jetpack Compose APIs (Modifier.lumenGlass, LumenSource)
├── app/              # Interactive LumenGlass Studio testbed with real-time controls
└── benchmark/        # Macrobenchmark frame-timing verification harness
```

Every mathematical function and AGSL shader block in Lumen Glass is clean-room derived from analytical geometry, Snell's law, and Lamé superellipse curves. See [`CLEANROOM.md`](CLEANROOM.md) for the mathematical provenance record.

---

## License

Licensed under the **Apache License, Version 2.0**. See [`LICENSE`](LICENSE) for details.
