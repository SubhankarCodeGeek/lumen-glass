---
seo_title: "Liquid Glass Performance on Android: Measured Frame Timing"
crumb: Performance
description: >-
  Measured CPU frame time of Lumen Glass's AGSL liquid glass shader in Jetpack
  Compose, per quality tier (Macrobenchmark FrameTimingMetric, P50 to P99).
---

# PERF — measured frame timing

> **Purpose:** the evidence record for `PLAN.md` §6.1's performance exit criterion.
> **Method:** Macrobenchmark `FrameTimingMetric` (`:benchmark` module), measured on device.

**In short, for developers evaluating the library:** on the measured device the liquid glass
shader costs about **2.4ms of CPU per frame at the median** (HIGH tier 5.03ms vs the no-shader
fallback's 2.65ms), and every shader tier stays well inside a 16.6ms frame budget at the median.
The tiers differ in their *tail* (P95/P99), not their typical frame, so `QualityTier.LOW` is the
choice when occasional stalls matter more than dispersion and blur. These are one device's numbers,
measured with the display capped at 45Hz (see the warning below), not a cross-device guarantee.
How the tiers are picked and how to override them is covered in
[Getting started → Quality tiers]({{ '/docs/getting-started/' | relative_url }}#5-quality-tiers-and-what-each-device-gets).

---

## Measurement conditions

| | |
|---|---|
| Device | Motorola Edge 70 (`roadstr`), API 37, Adreno |
| Build | `benchmark` build type — **not debuggable**, unminified (see note below) |
| Compilation | `CompilationMode.Partial()` — `compilationMode: "verify"` in the result context |
| Iterations | 5 per tier, `StartupMode.WARM` |
| Screen | `TierComparisonActivity`, glass panes animating over high-contrast content |
| Screen (View path) | `ViewGlassActivity`, two View surfaces over a scrolling source |
| CPU | `cpuLocked: true` — clocks pinned by the benchmark, removing DVFS noise |
| Command | `./gradlew :benchmark:connectedBenchmarkAndroidTest` |

> [!IMPORTANT]
> **A `debug` build cannot be measured.** Macrobenchmark refuses with
> `AssertionError: ERRORS (not suppressed): DEBUGGABLE`. This is correct behaviour — a debug build
> carries extra checks and is JIT-dominated. It also means the app needs a `benchmark` build type
> that is non-debuggable **and** `profileable`, which is why `:app` has one.

---

## Results

**`frameDurationCpuMs`** — CPU time to produce a frame, in milliseconds. Budget is **16.6ms** (60fps).

| Tier | Effort | P50 | P90 | P95 | P99 |
|---|---|---|---|---|---|
| **FALLBACK** | no shader (frost panel) | **2.65** | 3.53 | 3.98 | 5.99 |
| **LOW** | lens, 0.5× capture, no blur | **4.96** | 10.15 | 11.58 | **13.44** |
| **HIGH** | lens + blur + dispersion + bezel, full-res | **5.03** | 6.86 | 10.92 | 19.61 |
| **BALANCED** | lens + blur, 0.75× capture | **5.19** | 7.37 | 10.93 | 21.00 |

**`frameOverrunMs`** — how far a frame missed its deadline. **Negative = under budget = no jank.**

| Tier | P50 | P90 | P95 | P99 |
|---|---|---|---|---|
| FALLBACK | −12.49 | −11.12 | −10.35 | −4.43 |
| LOW | −5.69 | −1.13 | +7.18 | **+18.01** |
| HIGH | −7.54 | +1.20 | +8.54 | +42.01 |
| BALANCED | −3.88 | +1.06 | +8.65 | +42.08 |

### View path (View/XML surfaces)

`ViewGlassFrameTimingBenchmark` drives `ViewGlassActivity` — the Phase 8 harness: one
`LumenGlassSourceLayout` containing a 48-row `ScrollView`, sampled by two `LumenGlassView`
surfaces (a `BALANCED` primary with 8dp frost plus a bezel, and a `HIGH` rim surface) while the
source scrolls. 5 iterations, `StartupMode.WARM`, same device and build type as above.

| Metric | P50 | P90 | P95 | P99 |
|---|---|---|---|---|
| `frameDurationCpuMs` | **3.9** | 5.3 | 6.3 | **9.6** |
| `frameOverrunMs` | −9.9 | −8.8 | −7.5 | −1.5 |

Read this as **“the View front end adds no separate cost model”**: it resolves the same
`GlassRenderPlan` and runs the same shader, so the tier tables above remain the cost reference.
The two scenarios are **not directly comparable** — different content, a different number of
surfaces, and the Compose screen animates while this one scrolls — so the lower numbers here are
not evidence that one backend is faster. The 45Hz governor caveat applies to this run too.

---

## What this says

1. **The glass costs ~2.4ms of CPU per frame at the median.** HIGH (5.03ms) versus FALLBACK
   (2.65ms) is the shader's price. That is the number worth knowing, and it is small.

2. **All shader tiers are comfortably under the 16.6ms budget at the median.** HIGH sits at
   −7.5ms overrun — i.e. ~9ms of headroom.

3. **The median does not distinguish the tiers; the tail does.** P50 clusters at ~5ms for every
   shader tier, but P99 spreads from 13.4ms (LOW) to 21.0ms (BALANCED/HIGH). The expensive effects
   (dispersion, bezel, full-resolution capture) show up as **occasional stalls**, not as a higher
   baseline. Anyone tuning this should look at the 95th/99th percentile, not the average.

4. **LOW has the best tail (P99 13.4ms, no overrun until P95)**, which is the tier policy doing what
   it claims: the reduced capture scale plus no blur removes the worst-case spikes. Recording and
   lens evaluation happen at buffer resolution and the presentation upscale is a GPU composite; the
   per-stage saving has not been isolated by a benchmark, so this is measured at the whole-frame
   level only.

> [!WARNING]
> **The device caps this app's UID to 45Hz, so these are not 60fps measurements.** Measured
> immediately before and after the run:
> ```
> frameRateOverride {uid=10429 frameRateHz=45.0}
> ```
> The system (Motorola's `MotoBatteryCareService`) pins the UID; battery saver is off and the app
> requests `Default`. **Macrobenchmark did not defeat it.**
>
> Why the numbers are still worth having: `frameDurationCpuMs` measures **CPU work per frame**,
> which is independent of the display's frame rate. It answers "how much does the glass cost?" even
> though it cannot answer "how many fps does the user see?". The `frameOverrunMs` figures *are*
> rate-dependent (the deadline is 22.2ms at 45Hz, not 16.6ms), so the overrun column is optimistic —
> read it as "comfortable headroom", not as a 60fps verdict.

---

## Exit criterion status

> [!CAUTION]
> **`PLAN.md` §6.1 is NOT discharged.** It requires **≥60fps on a low-end reference device**, and
> two independent problems block that here:
>
> 1. **No such device.** The Edge 70 is the *mid-range* baseline (PQ2), not a low-end reference. Its
>    numbers say nothing about an entry-level device.
> 2. **The 45Hz governor** makes a frame-rate verdict unmeasurable on this hardware regardless.
>
> The CPU-cost data above is real and useful; the *frame-rate criterion* remains **UNMET**.

### What would discharge it

- A device without the frame-rate override (or with it disabled) — needs a root/managed device.
- A genuine low-end reference device, per PQ2.

#### App-side frame-rate requests — TRIED, INEFFECTIVE

> [!WARNING]
> **Do not repeat this experiment.** The app-side levers were tested on 2026-10-09 and do not work
> here.
>
> `TierComparisonActivity` calls the only window-level frame-rate levers the platform offers a plain
> `View` hierarchy:
> ```kotlin
> window.setFrameRateBoostOnTouchEnabled(true)
> window.setFrameRatePowerSavingsBalanced(false)
> ```
> (`Surface.setFrameRate` is not usable here — it needs a `Surface`, and only a `SurfaceView` owns
> one, so it is not reachable from a `View`-based screen.)
>
> Result: the governor **re-applied 45Hz regardless**, and did so even to a freshly-installed UID:
> ```
> before launch:  frameRateOverride {uid=10429 frameRateHz=45.0}
> after launch:   frameRateOverride {uid=10463 frameRateHz=45.0}
> in-app HUD:     fps ~45.5  avg 22.0ms
> ```
> The calls are left in place (harmless, and they *would* help on a device without a governor) but
> they are **not** a workaround on this hardware. A root/managed device or a different device is
> required.

---

## Reproducing

```bash
ANDROID_PREFS_ROOT= ./gradlew :benchmark:connectedBenchmarkAndroidTest

# View/XML surfaces only (Phase 8):
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.subhankar.lumenglass.benchmark.ViewGlassFrameTimingBenchmark
```

> [!NOTE]
> **`ANDROID_PREFS_ROOT` must be unset.** If it and `ANDROID_USER_HOME` are both set — even to the
> same path — AGP fails with `AndroidLocationsException: Several environment variables ... contain
> different paths to the Android Preferences folder`, which surfaces as a bewildering
> `Failed to create service AndroidLocationsBuildService` on *every* build. This cost real time to
> diagnose; hence the note.
