# Lumen Glass — Architecture

> Target architecture for the **Lumen Glass** library — a standalone Android project living in a
> **sibling folder** next to `360 Community` (`../LumenGlass/`). **Not** part of the 360 build.
>
> See [`PLAN.md`](PLAN.md) for the *why*, the licence constraint, and the roadmap.
> See [`CLEANROOM.md`](CLEANROOM.md) before writing any shader code.

---

## 1. Layout

```
../LumenGlass/                     # separate project root (own wrapper/settings/build)
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/*
│
├── core/                          # PLATFORM-AGNOSTIC — no `android`/`androidx` imports
│   ├── LensModel.kt               # Centre / Rim / Blend(centre, rim) — the differentiator
│   ├── GlassSpec.kt               # typed optical config (see §3)
│   ├── QualityTier.kt             # HIGH / BALANCED / LOW / FALLBACK + selection policy
│   └── GlassMath.kt               # pure maths: SDF, gradient normal, falloff, dispersion offsets
│
├── android/                       # Android actuals
│   ├── GlassSource.kt             # capture modifier — records source content into a layer
│   ├── GlassEffect.kt             # draw modifier — samples the layer, renders the material
│   ├── DownscaleCapture.kt        # reduced-resolution capture (perf, §5)
│   ├── LumenGlassShader.kt        # CLEAN-ROOM AGSL, tiered, as a Kotlin `const val` string
│   └── LumenGlassState.kt         # holds the shared capture state
│
├── compose/                       # public Compose API — what consumers import
│   ├── LumenGlass.kt              # Modifier.lumenGlass(...), Modifier.lumenSource(...)
│   └── rememberLumenGlassState()
│
├── sample/                        # demo app: sliders over high-contrast content
└── README.md  ARCHITECTURE.md  CLEANROOM.md
```

For **P1** this is a **single Gradle module** with `core/`/`android/`/`compose/` **source sets**.
It splits into real submodules only at **P5** (packaging). Rationale: simplest to build/test now;
the split is a mechanical move later.

---

## 2. The `core/` purity rule (enforced)

> [!IMPORTANT]
> `core/` **must not** import `android.*` or `androidx.*`. It is pure Kotlin so it can be reused by
> KMP targets (Q1: Android-first, KMP-shaped) and so it is **JVM-unit-testable** without a device.

**Enforcement:** a unit test (`CorePurityTest`) scans the `core/` sources and **fails** if any import
starts with `android`/`androidx`. This is a guard, not a convention — a clean build is not proof.

---

## 3. The model (`core/`)

### 3.1 `LensModel` — the differentiator

Every existing Android glass lib is **rim-only**. Lumen Glass offers both models and a blend:

```kotlin
sealed interface LensModel {
    data class Centre(val weight: Float) : LensModel          // whole-face magnification/warp
    data class Rim(val weight: Float) : LensModel             // edge-band bend (the incumbent model)
    data class Blend(val centre: Float, val rim: Float) : LensModel
}
```

`core/` resolves a `LensModel` into the **weight field** consumed by the shader (pure maths — no
GL types). This is what the shader's `centreWeight`/`rimWeight` uniforms are driven from.

### 3.2 `GlassSpec` — typed optical config

The 7-part v1 effect set (Q3), all typed, all with sane defaults:

| Field | Meaning |
|---|---|
| `lens: LensModel` | centre / rim / blend |
| `refraction: Float` | overall displacement strength |
| `dispersion: Float` | chromatic aberration (0 = off) |
| `frost: Dp` | blur radius (0 = no blur) |
| `tint: Color` | material colour (expects its own alpha) |
| `saturation: Float` / `contrast: Float` | colour grading |
| `edgeLight: Float` | specular rim band |
| `bezel: Bezel?` | optional inner shadow |

### 3.3 `QualityTier` — perf policy

`enum { HIGH, BALANCED, LOW, FALLBACK }`. `core/` maps a tier → the **feature set** that runs
(which taps, whether dispersion/bezel are on). The tier is selected from a capability probe, with a
**user override**; a downgrade must be **app-detectable**, never silent (see `PLAN.md` §5.2).

### 3.4 `GlassMath` — pure helpers

SDF rounded-rect distance, gradient normal, falloff curves, dispersion offsets. Used by both the
unit tests and (as the contract) the shader. Keeping the maths here means the shader mirrors tested
behaviour rather than being an untested blob.

---

## 4. The substrate (`android/`) — owned, not borrowed

> Q2: Lumen Glass owns its capture + effect pipeline. **No `backdrop` dependency.**

```
source content ──Modifier.lumenSource(state)──▶ GraphicsLayer (captured)
                                                     │
glass surface ──Modifier.lumenGlass(state){…}────────┤
                                                     ▼
   RenderEffect.createRuntimeShaderEffect(shader, "content")
        = blur (separable) + lens (centre/rim/blend) + [dispersion] + [bezel]
                                                     ▼
                                                  draw
```

**The pitfall (learned the hard way):** the glass surface **must overlap the region recorded into
the capture layer**, or the shader samples empty space and renders **near-black**. The API must make
this impossible to hit silently — warn in debug, and document it. (This is a first-class design
requirement, not a footnote.)

---

## 5. Performance strategy

`PLAN.md` §5.2 has the full table. The architecture implications:

- **`DownscaleCapture`** — capture renders at 0.25×–0.5× linear scale (glass is blurry anyway), then
  the effect samples the small layer. ~4× fragment work saved.
- **Separable blur** — horizontal + vertical passes (18 taps vs 81), not a single 9×9.
- **Render-once** — the capture layer is only re-recorded when content behind the surface changes.
- **Tier-gated effects** — dispersion (the most expensive) and bezel only run in HIGH.
- **FALLBACK** — no shader at all (API < 33 / non-AGSL): a clean frosted panel that looks intentional.

---

## 6. Public API (`compose/`)

```kotlin
val state = rememberLumenGlassState()      // creates/owns the capture

Box {
    ScrollingContent(Modifier.lumenSource(state))     // marks the captured source
    Card(
        Modifier.lumenGlass(state) {
            lens       = LensModel.Blend(centre = 0.6f, rim = 0.4f)
            refraction = 1.0f
            dispersion = 0.35f
            frost      = 6.dp
            tint       = Color(0xFFFF3B30).copy(alpha = 0.40f)
            edgeLight  = 0.1f
            bezel      = Bezel.soft(radius = 12.dp, alpha = 0.12f)
        }
    )
}
```

**Principles**
- **Overlap-safe** (see §4).
- **Token-driven** — typed values, never magic numbers.
- **Graceful** — documented FALLBACK below API 33.
- **Guarded** — a structural test asserts every bound uniform is declared (the `unknown identifier`
  crash class).

---

## 7. Testing

| Test | Scope |
|---|---|
| `GlassMathTest` | SDF distance, gradient normal, falloff, dispersion offsets (JVM) |
| `QualityTierTest` | tier → feature resolution + override behaviour (JVM) |
| `LensModelTest` | centre/rim/blend weight resolution (JVM) |
| `CorePurityTest` | `core/` imports nothing `android*`/`androidx*` |
| `LumenGlassShaderStructureTest` | every bound uniform is declared in the shader string |

Device verification: the **sample app** (harness pattern) renders the shader over high-contrast
content; frame timing measured for the §6.1 budget.

---

## 8. Relationship to `360 Community`

- Lumen Glass is a **separate project** — nothing in `360 Community` depends on it, and it is not in
  that repo's `settings.gradle.kts`.
- The app's current `:designsystem/theme/CommunityRefraction.kt` is the **R&D prototype**; it remains
  the app's internal glass until the **PQ3 migration** (post-P3.5).
- The design system's shader is a **port of an unlicensed snippet** and is **not** carried over —
  Lumen Glass re-derives it clean-room (see `CLEANROOM.md`).
