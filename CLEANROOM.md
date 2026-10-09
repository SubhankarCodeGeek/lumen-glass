# CLEANROOM — shader provenance record

> **Status:** 🟢 Complete (P1.4). Shader written and verified compiling/rendering on device (P1.7).
> **Read this before writing any shader code.**

---

## Why this file exists

The 360 Community app's glass shader
(`:designsystem/.../theme/CommunityRefraction.kt`, `LIQUID_AGSL`) is a **port of a public
Shadertoy-style GLSL snippet that carried no licence header**. A library published from that code
would expose every consumer to legal risk.

Lumen Glass therefore ships a **clean-room** shader: the same *behaviour*, written **from the
mathematics**, with **no third-party shader text copied**. This file is the **receipt** for that.

---

## The clean-room rules (from `PLAN.md` §3)

1. Write the shader **from the mathematical description** — SDF distance → gradient normal → edge
   falloff → sample displacement — **not** by copying any existing shader's text.
2. Record here the **maths references** used (public, citable).
3. **Do not** read `FletchMcKee/liquid`'s or `backdrop`'s shader source while writing ours.
   (Inspiration at the API/behaviour level is fine; copying code is not.)
4. Any dependency we keep must be **permissively licensed** and credited in `THIRD_PARTY_LICENSES`.

---

## Maths references used

All references are **generic, public computer-graphics sources**. None is a glass shader, and no
text was taken from any glass implementation.

| Concept | Reference | Notes |
|---|---|---|
| Rounded-rect SDF | Inigo Quilez, *2D distance functions* — <https://iquilezles.org/articles/distfunctions2d/> | Standard closed-form rounded-box distance. Public article. |
| Aspect-agnostic superellipse field | Superellipse / Lamé curve — standard analytic geometry | Normalizing each axis to `-1..1` and taking `|x|^e + |y|^e` is textbook; our exponent is a tunable uniform. |
| Edge falloff | Smoothstep (Hermite) interpolation — GLSL ES 1.0 spec, `smoothstep` | The reference function is applied to the SDF; no third-party glass code involved. |
| Separable box / Gaussian blur | Standard image-processing literature (separable convolution) | Textbook technique. **Not yet implemented** — see P3.5. |
| Chromatic dispersion | Per-channel radial scale offsets | Documented as our own derivation below. |
| Colour grading (saturation, contrast) | Rec. 709 luma coefficients; standard saturation/contrast algebra | `luma = dot(rgb, (0.2126, 0.7152, 0.0722))`. |

---

## Derivation notes (our own algebra)

These are the derivations the shader is a transliteration of. The pure implementations live in
[`GlassMath.kt`](library/src/main/java/com/subhankar/lumenglass/core/GlassMath.kt) and are
JVM-unit-tested, so the shader mirrors *tested* behaviour rather than an untested blob.

### 1. Coordinate space — why we do not use the reference's mask

Glass surfaces are frequently **non-square** (a wide banner, a tall panel). Any mask formula that
multiplies the X term by a surface aspect ratio and a large constant is implicitly tuned to a
square canvas: off-square, it collapses toward zero and the lens silently becomes a no-op.

Our derivation avoids aspect entirely. For a point `p` measured from the surface centre and a
half-extent `halfSize`, define

```
n  = p / halfSize          // -1..1 on EACH axis, independent of aspect
sq = |n.x|^e + |n.y|^e     // 0 at the centre, ~1 at the border, for any aspect
```

`sq` is the **superellipse field**. Every downstream term is derived from `sq` alone, so the effect
is shape-independent by construction rather than by tuning. (This is a deliberate divergence from
the reference approach, and the single most important correctness fix in our shader.)

### 2. Lens displacement — the dual model

We want a displacement field that pulls the sample **toward the surface centre**, with two
independent components:

- a **centre** component strongest in the middle and zero at the rim,
- a **rim** component zero in the middle and strongest at the rim.

Since `sq` is `0` at the centre and `~1` at the rim, the two natural weights are:

```
centreTerm = (1 - sq) * centreWeight
rimTerm    =      sq  * rimWeight
weight     = (centreTerm + rimTerm) * refraction
offset     = -n * weight * scale
```

Setting `centreWeight = 1, rimWeight = 0` warps the **whole face**; `0, 1` bends only the edge (the
incumbent model); anything in between is the blend. This decomposition is our own, and it is what
makes the library's behaviour set strictly larger than the rim-only implementations.

### 3. Chromatic dispersion

Thick glass refracts different wavelengths by slightly different amounts, producing a colour fringe.
We model this as a **per-channel radial scale** about the centre:

```
redScale   = 1 + dispersion
greenScale = 1
blueScale  = 1 - dispersion
```

Each channel is sampled at the lens-displaced coordinate scaled by its own factor, then recombined
`(R from red tap, G from green tap, B from blue tap)`. At `dispersion = 0` all three scales are
identical and the cost collapses to a single tap — which is why the shader gates the three-tap path
behind `dispersion > 0` *and* the HIGH tier.

---

## What was and was not consulted

| Source | Consulted? | How |
|---|---|---|
| `FletchMcKee/liquid` shader source | ❌ No | Not read, per rule 3 |
| `io.github.kyant0:backdrop` shader source | ❌ No | Not read, per rule 3 |
| 360 Community `LIQUID_AGSL` | ⚠️ Behaviour only | Read **after** our shader was written, during the P2/P3 parity check. Used to compare *observable behaviour* and to confirm our aspect-ratio finding. **No text was copied.** |
| The unlicensed Shadertoy snippet | ❌ No | Never accessed directly; only known through the prototype's description of it |

> The 360 prototype's shader text is **not** carried into LumenGlass in any form — not as code, not
> as constants, not as structure. Its value to this project was the *problem list* it documented
> (the aspect-ratio collapse, the AGSL `unknown identifier` crash class), which we then solved
> independently.

---

## Attestation

> "The `LumenGlass` AGSL shader was written from the mathematical description of the algorithm and
> from generic public computer-graphics references listed above. No source text was copied from
> `FletchMcKee/liquid`, `io.github.kyant0:backdrop`, the 360 Community `LIQUID_AGSL`, or any other
> third-party glass shader."
>
> — *author / date:* `Subhankar Bag / Oct 9, 2026`

> [!NOTE]
> **Signed.** The attestation above is complete and the project owner has signed it, so the
> clean-room condition gating publication is satisfied. If the shader is materially re-derived in
> future (a new algorithm, not a tuning change), re-sign rather than assume this remains accurate.

---

## Contrast with the app prototype (for the record)

| | App prototype (`CommunityRefraction.kt`) | Lumen Glass |
|---|---|---|
| Origin | Port of an **unlicensed** Shadertoy snippet | **Clean-room** re-derivation from maths |
| Publishable? | ❌ No | ✅ Yes |
| Lens model | Centre-weighted only | Centre, rim **and** blend |
| Aspect handling | Patched post-hoc on top of a square-tuned mask | Aspect-agnostic by construction |
| `edge` uniform | Declared and bound, but **never used** in its shader | Applied |
| Relationship | R&D source of behaviour | Independent implementation |

The app prototype stays internal (it is the app's glass); the library never reuses its shader text.
