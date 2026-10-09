# P1 parity record — Lumen Glass vs the 360 Community prototype

> **Status:** 🟢 Completed.
> **What this is:** the record for `PLAN.md` §6's P1 exit criterion —
> *"harness shows equal-or-better result"* versus "the current effect".

---

## 1. What "parity" means here (and what it does not)

> [!IMPORTANT]
> **Parity is visual/behavioural, judged on device — not a pixel metric.**

Three things make a numeric comparison the wrong tool:

1. **The baseline is not licence-clean.** The prototype
   ([`CommunityRefraction.kt`](../../../360%20Community/designsystem/src/main/java/com/securnyx360/designsystem/theme/CommunityRefraction.kt))
   is a port of an unlicensed Shadertoy snippet and is marked *"internal R&D only — NOT
   publishable"* in its own header. Optimising for numerical closeness to it would be optimising for
   an artefact we are not allowed to ship.
2. **The two surfaces are not driven identically.** The prototype renders through `backdrop`'s
   `LayerBackdrop` pipeline with `vibrancy()` + `blur()` layered underneath, then an `InnerShadow`
   bezel on top. Lumen Glass renders through its own `RenderNode` capture with the lens, grading and
   tint inside a single shader. Comparing raw pixels would mostly measure the *pipeline* difference,
   not the lens.
3. **"Parity" is the wrong target.** P1's goal was never to reproduce the prototype — it was to
   re-derive the *behaviour* clean-room. Where we intentionally differ, difference is a **feature**.

**Therefore:** parity was judged by eye, side by side, on the same device, with both surfaces over
the same high-contrast content. The judgement is recorded below along with every known difference.

---

## 2. Method

| | |
|---|---|
| Device | Motorola Edge 70 (`roadstr_g`), API 36, Adreno |
| Content | High-contrast scrolling list (`Item N — LUMEN GLASS P1.7`), identical in both harnesses |
| Lumen Glass surface | `:app` harness — `ComposeHarnessActivity` / `LensComparisonActivity` |
| Prototype surface | 360 Community's own glass harness, same device |
| Judgement | Visual, by the project owner, side by side |
| Metric | None — deliberately |

---

## 3. Outcome

**Result: met.** Lumen Glass renders a materially equivalent glass over the same content, with the
aspect-ratio correctness fix (§4.1) making it visibly *more* reliable on non-square surfaces —
which is the common case in a real layout.

The stronger conclusion is in §4: the library's behaviour set is a **strict superset** of the
prototype's. Parity holds trivially where the models overlap, and the library adds behaviours the
prototype cannot express.

---

## 4. Known and intentional differences

These are recorded deliberately. A parity record that lists only "looks the same" is not a parity
record; it is marketing.

### 4.1 Aspect ratio — the prototype's most serious defect

The prototype's shader multiplies its mask's X term by the surface aspect ratio and a large
constant, tuned for a square-ish canvas. On a wide surface (the prototype's own header notes a
~5.9:1 banner) that formula **collapses toward zero and the lens silently becomes a no-op** — the
glass renders, but nothing bends. The prototype patches around this by computing an aspect-agnostic
squircle distance *after* the fact.

Lumen Glass does not have the defect to patch: the superellipse field `|n.x|^e + |n.y|^e` over
per-axis-normalised coordinates is aspect-independent **by construction**
([`GlassMath.squircle`](../library/src/main/java/com/subhankar/lumenglass/core/GlassMath.kt)).

**Verdict: Lumen Glass better.** Not a divergence in behaviour — the prototype's behaviour here is a
bug.

### 4.2 Lens model — centre-only vs centre/rim/blend

The prototype's lens is **centre-weighted only** (the "full-surface lens"). This was, per
`PLAN.md` §2.1, the whole reason the library exists: every open-source Android implementation found
was **rim-only**, and the prototype demonstrated the centre-weighted alternative.

Lumen Glass implements **both, plus a tunable blend** — `LensModel.Centre`, `LensModel.Rim`,
`LensModel.Blend`.

**Verdict: Lumen Glass strictly superset.** `LensModel.Centre` reproduces the prototype's behaviour;
`Rim` and `Blend` are additive.

### 4.3 Dead `edge` uniform

The prototype declares `uniform float edge;` and binds it from Kotlin
(`setFloatUniform("edge", edge)`), but **the shader never reads it** — an unused convention.
(It is not a crash, because the name *is* declared; it is simply dead.)

Lumen Glass's `edgeLight` is applied — the shader derives an edge band from the signed distance and
adds it to the colour.

**Verdict: Lumen Glass better.** The prototype has a knob that does nothing.

### 4.4 Blur — cost and placement

The prototype performs a **9×9 box blur inside the lens shader**: 81 `content.eval()` taps per pixel,
before the lens and lighting maths. `PLAN.md` §5.2 identifies exactly this as the reason shader
compile fallbacks were observed on mid-range Adreno hardware.

Lumen Glass keeps blur **out of the lens shader** — the separation of concerns is already in place
(`QualityTier.resolveFeatures()` exposes `useSeparableBlur` and `captureScale`), and the separable
2-pass implementation is P3.5.

> [!NOTE]
> **Stated honestly:** as of this record the prototype's blur is the *more complete* one, because it
> exists. Lumen Glass currently renders without the 81-tap blur, which is why it is faster. This is
> a **known, planned** gap, closed at P3.5 — not a claim of superiority. The architecture is ready
> for it; the implementation is not yet written.

**Verdict: prototype more complete today; Lumen Glass's architecture is the right one.**

### 4.5 Tint and grading

The prototype blends a tint (`mix(fragColor.rgb, tint.rgb, tint.a)`) but does no saturation/contrast
work in the shader — its `vibrancy()` step is a separate `backdrop` effect.

Lumen Glass performs tint **and** saturation/contrast grading inside the shader
(`gradeColor`), driven by typed `GlassSpec` values.

**Verdict: equivalent surface, different placement.** Not a behavioural divergence.

### 4.6 Bezel

> [!NOTE]
> **Updated at P3.5 — this gap is closed.** The text below is the P1-era finding, kept for the
> record.

The prototype applies an `InnerShadow` bezel as a separate `backdrop` effect in the chain.

Lumen Glass declares `Bezel` in `GlassSpec` but **does not render it yet** — the shader has no bezel
path, and `QualityTier.resolveFeatures()` gates `useBezel` to the HIGH tier only.

**Resolved at P3.5:** the bezel is now rendered **inside the shader**, as a dark band derived from
 the signed distance the mask already computes. That costs almost nothing (no second pass, no extra
tap) and keeps the "own the substrate" decision intact — the prototype needed a separate
`InnerShadow` effect from a third-party library to do the same thing.

**Verdict: prototype had it first; Lumen Glass now does it with less machinery.**

---

## 5. Summary

| Dimension | Prototype | Lumen Glass | Better |
|---|---|---|---|
| Aspect handling | Bug + post-hoc patch | Correct by construction | **Lumen** |
| Lens model | Centre only | Centre / Rim / Blend | **Lumen** |
| `edge`/edge light | Dead uniform | Applied | **Lumen** |
| Tint + grading | Tint only (vibrancy is external) | Tint + saturation/contrast in-shader | Equivalent |
| Licence | ❌ Unlicensed port | ✅ Clean-room | **Lumen** |
| Blur | 9×9 in-shader (81 taps) | Platform `createBlurEffect` (separable, hardware) | **Lumen** (P3.5) |
| Bezel | Separate library effect | In-shader, near-free | **Lumen** (P3.5) |

**P1 exit criterion: met** — equal-or-better visually. **Both P1-era gaps (blur, bezel) are now
closed at P3.5**, and the blur row inverted: what looked like the prototype being ahead turned out
to be the prototype doing expensive work the platform already does better.

---

## 6. Clean-room note

The prototype was read for **behaviour**, after our shader was already written, and specifically to
confirm the aspect-ratio finding recorded in §4.1. **No shader text, constant or structure was
copied** from it into Lumen Glass. See [`CLEANROOM.md`](../CLEANROOM.md) §"What was and was not
consulted".
