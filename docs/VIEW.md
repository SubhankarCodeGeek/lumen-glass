---
seo_title: "View & XML: Liquid Glass for Android Views (LumenGlassView)"
crumb: Views
description: >-
  Use Lumen Glass without Jetpack Compose: LumenGlassView, LumenGlassSourceLayout, the XML
  attribute reference, the source model, coordinate mapping, lifecycle and limitations.
---

# Lumen Glass for Android Views

The `view/` package is the **View/XML** front end for the same glass engine the Compose API uses.
It is not a port and not a second implementation: `LumenGlassView` builds the same `GlassSpec`,
resolves the same `GlassRenderPlan`, and runs the same AGSL shader through the same
`GlassCapture`/`RenderEffect` substrate as `Modifier.lumenGlass`.

```xml
<com.subhankar.lumenglass.view.LumenGlassSourceLayout
    android:id="@+id/backdrop"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- Anything that should appear behind the glass lives here. -->
</com.subhankar.lumenglass.view.LumenGlassSourceLayout>

<com.subhankar.lumenglass.view.LumenGlassView
    android:layout_width="280dp"
    android:layout_height="160dp"
    app:lumenGlassSource="@id/backdrop"
    app:lumenGlassCornerRadius="24dp"
    app:lumenGlassFrost="8dp" />
```

---

## Requirements

| Requirement | Detail |
|---|---|
| **API 33+** | `RuntimeShader`/AGSL. Below that the surface draws the shared FROST panel. |
| **Hardware acceleration** | A software canvas cannot replay a `RenderNode`; the source falls back to an ordinary `FrameLayout` and the glass to FROST. |
| **A `LumenGlassSourceLayout` source** | No other view type can be sampled — see [The source model](#the-source-model). |
| **Same window** | A source in another window has no meaningful offset to the surface. |

The library itself supports API 21+. On API 29–32 the View hierarchy still works, but no glass is
rendered: `LumenGlassSourceLayout` behaves as a normal `FrameLayout` and `LumenGlassView` draws the
FROST panel.

---

## The source model

### Only a `LumenGlassSourceLayout` can be sampled

An arbitrary sibling cannot be captured correctly. Recording a random view into a `RenderNode`
loses the parent transform, alpha and clip that positioned it, and it is rasterised twice. So the
library makes the supported source an explicit type, and the binding layer **rejects everything
else with a diagnosable reason** rather than silently rendering a near-black surface.

### Declare the glass after the source

The source records its content during its own draw. A glass declared **after** the source samples
the frame that was just recorded. A glass declared **before** it samples the previous frame and is
refreshed on the next one — one frame stale, not wrong.

### The source and the glass are siblings, never ancestor/descendant

A glass inside its own source would recurse through itself. `lumenGlassSource` references that
resolve to the glass itself or to one of its ancestors are rejected (`SELF_REFERENCE`,
`ANCESTOR_REFERENCE`). This is also what makes invalidation loop-free: a surface can never dirty
the source it samples.

### One source feeds many surfaces

The source owns **one** effect-free capture node. Each `LumenGlassView` owns its own capture and
its own cached `RenderEffect`, so two surfaces over the same source can have different lenses,
tints and tiers. The source content is recorded once per source invalidation regardless of how many
surfaces sample it.

### What is captured

On each hardware draw, `LumenGlassSourceLayout` records, in `View.draw()` order:

1. the background,
2. `onDraw`,
3. every direct non-glass child.

Then it draws that node, direct `LumenGlassView` children (outside the node, so a glass inside a
source cannot create a `RenderNode` cycle), and the foreground.

`SurfaceView` and `TextureView` content is not part of the View draw hierarchy and is not captured.

### Freshness while scrolling

After recording, the source posts an invalidation to every bound surface. Scrolling a `ScrollView`
invalidates the source, so the capture re-records and the glass refreshes. A surface that moves
independently (a fixed panel over a scrolling list, a RecyclerView item) also invalidates on
`onScrollChanged` and on layout-position changes.

---

## Attribute reference

All attributes map onto the shared `GlassSpec`. **An unset attribute never overrides a value**: the
reader stores `null` and `toGlassSpec()` leaves the base spec alone. That is what lets the same XML
support the library's [presets]({{ '/docs/ARCHITECTURE.html' | relative_url }}) without duplicating
defaults.

| Attribute | Format | Unset behaviour (plain view) | Maps to |
|---|---|---|---|
| `lumenGlassSource` | reference | unbound → FROST | the source to sample |
| `lumenGlassEnabled` | boolean | `true`; `false` = transparent `FrameLayout` | on/off |
| `lumenGlassQuality` | enum `auto\|high\|balanced\|low\|fallback` | `auto` = capability selection | `QualityTier` override |
| `lumenGlassLensModel` | enum `centre\|rim\|blend` | current lens unchanged | `LensModel` |
| `lumenGlassCentreWeight` | float | `0.6` when a lens model is set; clamped `0..1` | centre weight |
| `lumenGlassRimWeight` | float | `0.4` when a lens model is set; clamped `0..1` | rim weight |
| `lumenGlassRefraction` | float | `1.0`; clamped `≥ 0` | `refraction` |
| `lumenGlassDispersion` | float | `0`; clamped `≥ 0` | `dispersion` |
| `lumenGlassFrost` | dimension (dp) | `6dp`; clamped `≥ 0` | `frostDp` |
| `lumenGlassTint` | color (ARGB) | no tint | `tintArgb` |
| `lumenGlassSaturation` | float | `1`; clamped `≥ 0` | `saturation` |
| `lumenGlassContrast` | float | `1`; clamped `≥ 0` | `contrast` |
| `lumenGlassEdgeLight` | float | `0.1`; clamped `0..1` | `edgeLight` |
| `lumenGlassCornerRadius` | dimension (dp) | `0dp`; clamped `≥ 0` | `cornerRadiusDp` |
| `lumenGlassPrecisionMode` | boolean | `true` | `precisionMode` |
| `lumenGlassRimProfile` | enum `toric\|smooth\|droplet\|legacy` | `toric` | `rimProfile` |
| `lumenGlassBezelRadius` | dimension (dp) | **no bezel**; clamped `≥ 0` | `Bezel.radiusDp` |
| `lumenGlassBezelAlpha` | float | no bezel; `0.12` when only the radius is set; clamped `0..1` | `Bezel.alpha` |

> [!NOTE]
> `lumenGlassBezelRadius` and `lumenGlassBezelAlpha` opt **into** a bezel. Setting only one of them
> keeps the other from the base spec (or the `Bezel` default), so a partial override cannot erase a
> preset's bezel. A bezel and dispersion are rendered on the `HIGH` tier only.

Units: every length attribute is declared in **dp** and divided by density once, at parse time.
`GlassSpec` stores dp; the render plan multiplies by density at draw time. There is no place where
a length is scaled twice.

Unknown enum values are ignored with a debug log rather than thrown — a bad resource must not crash
a draw.

---

## Kotlin API

```kotlin
val glass = findViewById<LumenGlassView>(R.id.glass)
val source = findViewById<LumenGlassSourceLayout>(R.id.backdrop)

glass.setSource(source)                 // or glass.setSource(R.id.backdrop)
glass.setQualityOverride(QualityTier.HIGH)
glass.setGlassConfig {
    lens = LensModel.Blend(centre = 0.6f, rim = 0.4f)
    refraction = 1.0f
    dispersion = 0.0f
    frostDp = 8f
    cornerRadiusDp = 24f
    bezel(radiusDp = 10f, alpha = 0.12f)
}
```

| Member | Purpose |
|---|---|
| `glassSpec` | the material currently configured |
| `source` | the bound `LumenGlassSourceLayout`, or `null` |
| `isGlassEnabled` | whether glass (not just layout) is on |
| `resolvedTier` | the tier actually in effect after selection/clamping |
| `resolvedFeatures` | the effect set that tier enables |
| `canRunShader` | `true` on API 33+ |
| `setGlassSpec(GlassSpec)` | replace the material with a built spec |
| `setGlassConfig { … }` | replace the material through `LumenGlassConfigBuilder` |
| `setGlassEnabled(Boolean)` | turn the surface into a plain transparent `FrameLayout` |
| `setQualityOverride(QualityTier?)` | force a tier; `null` restores selection |
| `setSource(LumenGlassSourceLayout?)` | bind or unbind |
| `setSource(@IdRes Int)` | resolve a resource id in this view's tree |
| `invalidateGlass()` | drop the cached capture/effect and redraw |
| `release()` | free native capture/effect; idempotent, recreated on the next draw |

Constructing a glass in code (no XML) follows the same rules:

```kotlin
val glass = LumenGlassView(context)
glass.setSource(sourceLayout)
glass.setGlassSpec(GlassSpec.AppleDark)
parent.addView(glass)
```

`LumenGlassConfigBuilder` clamps the same values the XML reader does (lengths `≥ 0`,
`edgeLight`/alpha `0..1`), and its `build()` default equals `GlassSpec.Default`.

### Quality tiers

`lumenGlassQuality="auto"` (the default) uses the same policy as Compose: `BALANCED` on API 33+,
`FALLBACK` below, with overrides clamped to what the device can run. Read `resolvedTier` to see
what was applied — never assume the override took effect.

| Tier | Shader | Blur | Capture scale | Dispersion / bezel |
|---|---|---|---|---|
| `HIGH` | yes | yes | 1.0 | yes |
| `BALANCED` | yes | yes | 0.75 | no |
| `LOW` | yes | no | 0.5 | no |
| `FALLBACK` | no (FROST panel) | no | 0.5 | no |

---

## Limitations

- **Only `LumenGlassSourceLayout` sources.** Arbitrary siblings are rejected by design; see above.
- **`SurfaceView` / `TextureView` are not sampled.** Their pixels are not in the View draw tree.
- **Same window only.** A dialog or another activity is a different window; the surface falls back
  to FROST and logs the reason in debug.
- **Transitions that move a view without invalidating it** are not tracked beyond scroll and layout
  notifications. If an animation uses a `RenderNode`/`ViewPropertyAnimator` path that does not lay
  the view out, call `invalidateGlass()` when it completes.
- **A glass inside its own source is rejected**, not silently rendered.
- **No reduced "render once" caching yet**: the source re-records whenever it is invalidated. It
  does not record continuously on its own, but an animating backdrop does re-record every frame.

---

## Migrating from Compose

| Compose | View/XML |
|---|---|
| `rememberLumenGlassState()` | the `LumenGlassSourceLayout` instance itself |
| `LumenSource(state, modifier) { … }` | `<LumenGlassSourceLayout>` with the content as children |
| `Modifier.lumenGlass(state) { … }` | `<LumenGlassView app:lumenGlass…="…" />` or `setGlassConfig { … }` |
| `state.resolvedTier` / `resolvedFeatures` | `glass.resolvedTier` / `glass.resolvedFeatures` |
| `QualityTier` in the state factory | `app:lumenGlassQuality` / `setQualityOverride()` |
| `Dp` / `Color` at the Compose boundary | XML `dimension` (dp) / `color` (ARGB) |

Both APIs resolve through the same `GlassSpec` → `GlassRenderPlan` → AGSL path, so a material that
looks right in Compose looks right in Views, and a tuning change in `GlassSpec` affects both.

---

## Performance guidance

- **Cost scales with surfaces, not source size.** One source record is shared; each surface adds
  its own capture and effect pass. Prefer one surface where one will do.
- **Prefer `auto`.** It picks `BALANCED` on capable devices, and clamps a too-high override.
- **`lumenGlassFrost="0dp"` removes the blur pass** on any tier.
- **`LOW` cuts fragment work** with a 0.5× capture; it is the right choice for large surfaces on
  weak GPUs.
- **Do not declare full-screen glass without a reason.** The capture is proportional to the
  surface, and a full-screen shader pass is the most expensive configuration the library offers.

The 60 fps criterion remains an open, device-specific measurement; see
[Performance]({{ '/docs/PERF.html' | relative_url }}). No
frame-rate claim is made here. The sample harness (`ViewGlassActivity`) is a correctness harness,
not a benchmark.

---

## Debugging

```kotlin
LumenGlassDebug.enabled = true
```

When enabled, the library logs (tag `LumenGlass`):

- a rejected `lumenGlassSource` with its reason and resolved class,
- a source/glass pair that does not share a window,
- the overlap verdict when the surface does not overlap the captured region (the ARCHITECTURE §4
  failure class).

With debugging off, none of these are computed beyond the checks the render path already needs.

---

## Verification

The View backend is covered by:

| Test | Scope |
|---|---|
| `SourceBindingTest` | binding matrix: missing / self / ancestor / wrong type (JVM) |
| `GlassAttrValuesTest` | XML values → `GlassSpec`, `null` preservation and clamping (JVM) |
| `LumenGlassConfigBuilderTest` | builder defaults, bezel opt-in, clamping (JVM) |
| `ViewGlassRenderTest` | pixel readback on a GPU: sampling and offset, multiple surfaces over one source, FROST fallback, idempotent/recoverable `release()`, children above the glass, XML attribute mapping, and source binding on attach (instrumented) |

The pixel tests render through `HardwareRenderer` with a **passthrough** spec (every optical term
off), so a colour assertion means "the source was sampled at the right offset", not "something
glass-coloured appeared".
