# CLEANROOM — shader provenance record

> **Status:** 🟡 Stub. To be completed alongside **P1.4** (the clean-room shader).
> **Read this before writing any shader code.**

---

## Why this file exists

The 360 Community app's glass shader
(`:designsystem/.../theme/CommunityRefraction.kt`, `LIQUID_AGSL`) is a **port of a public
Shadertoy-style GLSL snippet that carried no licence header**. A library published from that code
would expose every consumer to legal risk.

Lumen Glass therefore must ship a **clean-room** shader: the same *behaviour*, written **from the
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

## Maths references used *(to be filled in at P1.4)*

| Concept | Reference | Notes |
|---|---|---|
| Rounded-rect SDF | Inigo Quilez, *2D distance functions* (iquilezles.org) | Public article; standard formula |
| SDF gradient → normal | Inigo Quilez, *2D distance functions / gradient* | Public article |
| Edge falloff / lens displacement | *to be documented* | Describe the derivation in our own terms |
| Separable box/Gaussian blur | Standard image-processing literature | Textbook technique |
| Chromatic dispersion | *to be documented* | Per-channel sample offsets |

> Fill in the exact URLs and the derivation notes when the shader is written. Keep them **generic
> public maths references**, not links to specific third-party glass shaders.

---

## Attestation *(to be signed off at P1.4)*

> "The `LumenGlass` AGSL shader was written from the mathematical description of the algorithm and
> from generic public computer-graphics references listed above. No source text was copied from
> `FletchMcKee/liquid`, `io.github.kyant0:backdrop`, the 360 Community `LIQUID_AGSL`, or any other
> third-party glass shader."
>
> — *author / date:* `______________`

---

## Contrast with the app prototype (for the record)

| | App prototype (`CommunityRefraction.kt`) | Lumen Glass |
|---|---|---|
| Origin | Port of an **unlicensed** Shadertoy snippet | **Clean-room** re-derivation from maths |
| Publishable? | ❌ No | ✅ Yes (once this file is complete) |
| Relationship | R&D source of behaviour | Independent implementation |

The app prototype stays internal (it is the app's glass); the library never reuses its shader text.
