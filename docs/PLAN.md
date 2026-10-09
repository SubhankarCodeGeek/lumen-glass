# Lumen Glass — an open-source Liquid Glass rendering library (aspiration & plan)

> **Status:** 🟢 Decisions made (Q1–Q3, PQ1–PQ3, performance), P1 in flight.
> This document captures the *why*, the *gap*, the *licence constraint*, the *decisions*, and the
> *shape* of the work.
> **Read §3 (licence constraint) before writing any shader code.**
>
> **Location:** a **separate Android project** in a **sibling folder next to this repo**
> (working path `../LumenGlass/`). **Not** part of the 360 Community build.

---

## 1. The aspiration

Build and publish **our own Android (+ Compose Multiplatform) glass-rendering library** that:

- reproduces the **Apple iOS 26 "Liquid Glass"** look as faithfully as is achievable without
  Apple's (undisclosed) algorithm,
- **we own end-to-end** — our own AGSL shader, our own backdrop-capture/RenderEffect substrate,
  our own public API — **not** a wrapper over an existing library,
- exposes **tunable knobs** for the optical components (refraction, dispersion, frost, tint,
  edge/bezel lighting, whole-face vs rim-weighted lens),
- is **licence-clean**, so it can be published and others can build on it.

> **Working name:** _Lumen Glass_ (placeholder — rename freely).

---

## 2. Why us — the gap we would be filling

### 2.1 The competitive landscape (verified by reading their sources)

| Project | Lens model | Dispersion | Architecture | Licence / distribution |
|---|---|---|---|---|
| `io.github.kyant0:backdrop` (our current dep) | **rim-only** (`lens()` early-returns in the middle) | 7-tap chromatic (optional) | own `LayerBackdrop` + `drawBackdrop` | Apache-2.0 style, Maven |
| `FletchMcKee/liquid` v1.1.1 | **rim-only** (`lensDepth` saturates to 0 at centre) | per-channel R/B | own `LiquidState` + `liquefiable`/`liquid` | Apache-2.0, Maven, **KMP** |
| `Kyant0/AndroidLiquidGlass` | component layer over `backdrop` | via `backdrop` | components, not a library surface | — |
| Apple iOS 26 (reference) | *undisclosed* — reads as blurred-middle + bent-rim | yes | system | proprietary, iOS only |

**The gap:** every open-source Android implementation found is **rim-weighted only**. None offers:

1. a **whole-face lens** (content bends across the entire surface, centre included),
2. a **tunable blend** between centre-weighted and rim-weighted lens models,
3. a **documented, first-class substrate** with the pitfalls (e.g. the `LayerBackdrop`
   overlap constraint) encoded in the API rather than left as gotchas,
4. **Android + KMP** from one source.

Points 1–3 are things we have *already proven technically* in this app (see §5). This is a real,
specific problem — not "another glass wrapper".

### 2.2 What we already know (de-risking)

From the app's `EMERG-REFRACT-*` work we have working knowledge of:

- the **SDF → normal → displacement** refraction model,
- the **aspect-agnostic squircle mask** (the reference `pow(|x·aspect|,8)` + `*10000` constants
  collapse on non-square surfaces — we fixed this),
- the **`LayerBackdrop`/`RenderEffect` substrate** and the constraint that a glass surface
  **must overlap the recorded region** or it renders black,
- **AGSL gotchas** (string-compiled shaders; a bound-but-undeclared uniform throws
  `unknown identifier` at runtime → we built a guard test),
- the **performance shape** (9×9 blur + multi-tap dispersion is heavy; the Adreno driver logs
  shader-compile fallbacks).

---

## 3. THE LICENSING CONSTRAINT (read first — it gates everything)

> [!CAUTION]
> **Our current shader is a port of an unlicensed Shadertoy snippet.** It **cannot** ship in a
> published library. Any published version requires a **clean-room re-derivation**:
>
> **Rules for the clean-room pass:**
> 1. Write the shader **from the mathematical description** (SDF distance → gradient normal →
>    edge falloff → sample displacement), **not** by copying the snippet's text.
> 2. Keep a written **provenance record** (`CLEANROOM.md`): the maths references used
>    (e.g. Inigo Quilez's 2D SDF + gradient articles — public, citable), and a statement that no
>    third-party shader text was copied.
> 3. **Do not** look at `FletchMcKee/liquid`'s or `backdrop`'s shader source while writing ours.
>    (Inspiration at the **API/behaviour** level is fine; copying **code** is not.)
> 4. Any dependency we *do* keep must be **permissively licensed** (Apache-2.0/MIT) and credited
>    in `THIRD_PARTY_LICENSES`.
>
> **This is non-negotiable.** A library without a clean licence is worse than no library — it
> exposes every consumer to legal risk.

---

## 4. Decisions

> [!NOTE]
> **Q1 — Platform target: DECIDED = Android-first, KMP-shaped.**
> Write a **platform-agnostic core** (pure maths/model, no Android types) + **Android `actual`s
> now**; keep the seams open so iOS/desktop `actual`s can be added later without a rewrite. This
> avoids the immediate Kotlin 2.4 / AGP 9 toolchain bump that full KMP would force, while keeping
> the door open. **The core must never import `androidx`/`android.*`.**

> [!NOTE]
> **Q2 — Substrate ownership: DECIDED = OWN IT.** The library owns its own capture + `RenderEffect`
> pipeline (Android `RenderNode` — **not** `GraphicsLayer`, which is a hidden API; see §5.1); it
> does **not** depend on `backdrop`. This is
> what makes it a *library* rather than a wrapper. We will still credit `backdrop`/`liquid` as
> **inspiration** in the README, and document them as alternatives. See §5.1.

> [!NOTE]
> **Q3 — v1 effect set: DECIDED = the proposed set.**
> 1. **Lens:** whole-face (centre-weighted) + rim-weighted, with a **tunable blend** — the headline
>    differentiator.
> 2. **Dispersion** (chromatic aberration) — optional, tier-gated.
> 3. **Frost** (separable blur).
> 4. **Tint.**
> 5. **Saturation / contrast.**
> 6. **Edge / specular lighting.**
> 7. **Optional bezel inner-shadow.**
>
> All seven are exposed as typed config; the perf tier (§5.2) decides which actually run.

### 4.1 Performance is a first-class requirement (DECIDED)

> [!IMPORTANT]
> **The library MUST run acceptably on low-end hardware.** This is a design constraint, not a
> bolt-on — it shapes the shader, the capture path, and the public API. See §5.2.

---

## 5. Proposed architecture

```
../LumenGlass/                 # SEPARATE Android project (sibling of 360 Community)
├── settings.gradle.kts        # own project root + own Gradle wrapper
├── build.gradle.kts
├── gradlew / gradle/wrapper/*
├── core/                      # platform-agnostic: maths, model, config (NO Android/androidx types)
│   ├── LensModel.kt           # centre-weight / rim-weight / blend
│   ├── GlassSpec.kt           # refraction, dispersion, frost, tint, lighting, bezel
│   ├── QualityTier.kt         # High / Balanced / Low / Fallback + selection policy
│   └── (pure) maths helpers   # SDF, gradient, falloff, dispersion offsets
├── android/                   # Android actuals: RuntimeShader, RenderEffect, layer capture
│   ├── GlassSource.kt         # capture modifier (records content into a layer)
│   ├── GlassEffect.kt         # draw modifier (samples + renders the material)
│   ├── DownscaleCapture.kt    # renders the capture at reduced resolution (§5.2)
│   └── LumenGlassShader.kt    # CLEAN-ROOM shader (tiered), as a Kotlin const val string
├── compose/                   # public Compose API: Modifier.lumenGlass(...), LumenGlassState
└── sample/                    # demo app: sliders over high-contrast content (the harness, cleaned)
```

> For P1 this is a **single Gradle module** with `core/`/`android/`/`compose/` source sets.
> It splits into real submodules only at P5 (packaging).

### 5.1 Substrate (why we own it)

Owning the capture + `RenderEffect` pipeline is what makes this a *library* rather than a wrapper:

- **Capture:** render the source content into a `RenderNode` (the public capture primitive —
  `android.graphics.GraphicsLayer` is a hidden framework API and is **not** usable from a library
  built against the public SDK), off the main draw of the effect surface.
- **Effect:** apply `RenderEffect.createRuntimeShaderEffect(shader, "content")`, chaining blur + lens
  + (optional) dispersion, then draw.
- **The pitfall we learned:** the surface **must overlap the recorded region** or it renders black.
  The API should surface this (warn/auto-correct) rather than leave it as a gotcha.

### 5.2 Performance strategy (low-end hardware)

**The cost, honestly:** a naive 9×9 box blur = **81 `content.eval()` taps** per pixel + lens +
lighting. That is why we saw GPU shader-compile fallbacks on a mid-range Adreno. The plan:

| Technique | Saving | Notes |
|---|---|---|
| **Reduced-resolution capture** | ~4× fragment work at 0.5× | Glass is blurry by definition — the capture can render at 0.25×–0.5× linear scale with no visible loss |
| **Separable two-pass blur** (H + V) | 81 → 18 taps | Standard; mathematically equivalent for a box/Gaussian |
| **Render-once / cache** | ~0 when idle | A static surface only needs re-sampling when content behind it changes |
| **Optional dispersion** | −(N−1) taps | The single most expensive effect; off in low tiers |
| **Single-tap lens in low tier** | – | Skip the multi-sample lens path |
| **Skip frost (`blur=0`) when the source is already soft** | – | Configurable |

**Adaptive quality tiers** (chosen from runtime GPU capability, with a user override):

| Tier | Selects | Target |
|---|---|---|
| **HIGH** | full taps + dispersion + bezel | flagship GPUs |
| **BALANCED** | separable blur, dispersion optional, no bezel | mainstream |
| **LOW** | reduced-res capture + single-pass small blur + tint only | low-end / old GPUs |
| **FALLBACK** | no shader (API < 33 or non-AGSL) → plain frosted panel | everything else |

**Selection policy:** default from a capability probe (API level + a light GPU heuristic),
**overridable** via `LumenGlassState(quality = …)`, so apps can force LOW for battery or HIGH for
demos. **Never silently drop below the requested look without the app being able to detect it.**

**Non-negotiable:** the FALLBACK tier must look *intentional* (a clean frosted surface), not broken.

**Public API sketch:**

```kotlin
val state = rememberLumenGlassState()

Box {
    ScrollingContent(Modifier.lumenSource(state))          // records into the capture layer
    Card(Modifier.lumenGlass(state) {
        lens      = LensModel.Blend(centre = 0.6f, rim = 0.4f)   // ← the differentiator
        refraction = 1.0f
        dispersion = 0.35f
        frost      = 6.dp
        tint       = Color(0xFFFF3B30).copy(alpha = 0.40f)
        edgeLight  = 0.1f
        bezel      = Bezel.soft(radius = 12.dp, alpha = 0.12f)
    })
}
```

**Design principles**
- **Overlap-safe:** the API should *warn* (or auto-correct) when the glass surface doesn't overlap
  the recorded source — encoding the pitfall we hit.
- **Token-driven:** all optical constants are typed values, never magic numbers.
- **Graceful degradation:** below API 33 / on non-AGSL renderers → documented frosted fallback.
- **Shaders are strings → guard them:** ship a compile/structural test (as we already do).
- **Performance-budgeted:** every phase carries a frame-time budget; the sample app reports it.

---

## 6. Phased plan

| Phase | Deliverable | Exit criteria |
|---|---|---|
| **P0 — Decision** | Answer Q1–Q3 (§4); confirm licence approach | ✅ Done (this doc) |
| **P1 — Clean-room shader** | Re-derive the AGSL from the maths; parity with the current effect | ✅ Done — device-verified; `CLEANROOM.md` complete; parity recorded in `PARITY.md`. **Owner must still sign the `CLEANROOM.md` attestation.** |
| **P2 — Own substrate** | `GlassSource`/`GlassEffect` (capture + RenderEffect) without `backdrop` | Banner works with zero `backdrop` imports |
| **P3 — Dual lens model** | `LensModel` blend (centre/rim) + dispersion | Both modes demo-able in the sample |
| **P3.5 — Performance tiers** | Reduced-res capture, separable blur, adaptive tiers (§5.2) | **≥ 60fps on a low-end reference device** with the glass animating; tier auto-selection verified. **Also closes the two P1 gaps recorded in `PARITY.md` (blur, bezel).** |
| **P4 — Public API + module** | standalone module, `Modifier.lumenGlass`, KDoc | Compiles standalone; sample app runs |
| **P5 — Packaging** | Maven publishing (JitPack or Sonatype/`mavenCentral`), versioning, licences | A throwaway consumer project resolves + renders |
| **P6 — Polish & docs** | README, API docs, runbook, perf notes, goldens | Ready to announce |

Each phase is independently valuable; P1–P3.5 justify the work **even if we never publish**.

> [!IMPORTANT]
> **P1 is marked done but is not yet publishable.** `PARITY.md` §4.4 and §4.6 record two known gaps
> versus the prototype — in-shader blur and the bezel are not rendered by Lumen Glass yet. They are
> scheduled work for P3.5, not oversights, and the clean-room attestation still needs the owner's
> signature before anything ships.

### 6.1 Performance budget (acceptance)

> [!IMPORTANT]
> Performance is an **exit criterion**, not a nice-to-have. Proposed budgets:
> - **Flagship GPU** (HIGH tier): glass animating over scrolling content stays **≥ 60fps** (16.6 ms).
> - **Low-end reference** (LOW tier, e.g. an entry-level Mali/Adreno or an Android Go class device):
>   **≥ 60fps** with reduced-res capture + single-pass blur, **or ≥ 45fps** worst case with a
>   documented, app-detectable downgrade.
> - **Idle** (no content change): no per-frame GPU work (render-once / cache).
> - **Memory:** capture layer ≤ 1× a single screen bitmap at the chosen capture scale.
>
> Measure with Macrobenchmark + GPU frame timing in the sample app.

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| **Licence contamination** | Clean-room (§3) + provenance record; do not read others' shader text |
| Toolchain (KMP needs Kotlin 2.4 / AGP 9) | Android-first (Q1); KMP later if warranted |
| Apple-parity is unprovable | Position as *"closest faithful approximation, tunable"* — never claim pixel-perfection |
| Perf (multi-tap blur/dispersion) | **First-class requirement** (§4.1, §5.2, §6.1): reduced-res capture, separable blur, adaptive tiers, render-once. Exit criterion = 60fps on a low-end reference device. |
| GPU shader-compile fallbacks (seen on Adreno) | Keep shaders within AGSL constraints; tier down automatically; compile-guard test; never rely on a single heavy shader |
| Duplicating `liquid`/`backdrop` | Differentiate on the **whole-face + blend** model + active maintenance; keep theirs as documented alternatives |
| New project ≠ this repo's tooling | Sibling project has its **own** wrapper/settings/build; **not** in this repo's `settings.gradle.kts`; nothing in `:app` depends on it |
| Maintenance burden of a public lib | Only commit if P1–P4 land cleanly; publishing is optional, not mandatory |

---

## 8. Non-goals

- Claiming exact Apple parity (algorithm undisclosed).
- iOS-native rendering (Apple's own material is system-provided; we do Compose/KMP only).
- The app's internal `:designsystem` glass (that stays; this is a separate, reusable artefact).

---

## 9. Relationship to the current app work

The app's `EMERG-REFRACT-*` work is the **R&D + prototype** for this library. The current
`:designsystem/theme/CommunityRefraction.kt` + `GlassHarness.kt` remain the app's internal surface;
this plan **extracts and hardens** the reusable core into a separate project. Nothing here requires
changing the app's shipping behaviour.

---

## 10. Prerequisite decisions (RESOLVED)

> [!NOTE]
> **PQ1 — Placement: DECIDED.** A **separate Android project** in a **sibling folder next to this
> repo** (`../LumenGlass/`) — its own project root, own Gradle wrapper, own settings/build. **Not**
> added to this repo's `settings.gradle.kts`.
>
> **Doc handoff:** this `docs/lumen-glass/` bundle is copied into the sibling project at creation.

> [!NOTE]
> **PQ2 — Low-end reference device: DECIDED.** Perf target for §6.1 is the **Motorola Edge 70**
> (mid-range Adreno) as the mainstream baseline, **plus** a **software-rendered** emulator profile
> (lowest common denominator) for the LOW tier. Add a dedicated low-end physical device as the
> primary LOW reference if one becomes available.

> [!NOTE]
> **PQ3 — Existing glass code: DECIDED.** Long term, **migrate** the app's `:designsystem` glass
> onto Lumen Glass, using the current `CommunityRefraction.kt` as the behaviour baseline. This
> happens **after** P3.5; until then the app keeps its internal glass unchanged.

---

## 11. P1 — task breakdown (in flight)

**P1 = the clean-room, tiered shader + its pure-Kotlin model, in the standalone project.**

| Step | Deliverable |
|---|---|
| P1.1 | Scaffold the standalone Android project root (`../LumenGlass/`) — own Gradle wrapper (copied from 360 Community), settings, build. Single module with `core/`/`android/`/`compose/` source sets. |
| P1.2 | `core/` pure-Kotlin model: `LensModel` (centre/rim/blend), `GlassSpec`, `QualityTier` — **no `androidx`**; JVM-unit-tested |
| P1.3 | `CLEANROOM.md` provenance record (maths references; statement that no third-party shader text was copied) |
| P1.4 | `android/` clean-room AGSL shader — **tiered** (HIGH/BALANCED/LOW via a uniform), written from the maths |
| P1.5 | Pure-maths unit tests: SDF distance, gradient normal, falloff curves, dispersion offsets, tier selection |
| P1.6 | Structural shader guard test (every bound uniform declared) — the lesson from this app |
| P1.7 | Minimal render path proving the shader compiles + renders on device (harness pattern) |

**Exit criteria for P1:** the clean-room shader renders on device with **parity or better** versus the
current app effect (verified side-by-side in a harness); `CLEANROOM.md` exists; the shader compiles
and is structurally guarded. Ship-quality README/docs are **P6**, not P1.
