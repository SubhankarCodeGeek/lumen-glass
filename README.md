# Lumen Glass

An open-source **Android Liquid-Glass rendering library** — our own implementation of the Apple
iOS 26 "Liquid Glass" look: refraction, chromatic dispersion, frost, tint, edge lighting, and an
optional bezel, on a substrate **we own end-to-end**.

> **Status:** 🟢 Decisions made · **P1 in flight** (clean-room, tiered shader).
> This project is developed independently and is **not** built or shipped by the 360 Community app.

---

## Why this exists — the gap

Every open-source Android glass implementation we surveyed is **rim-weighted only** — the lens bends
content near the surface edge and leaves the middle flat:

| Project | Lens model |
|---|---|
| `io.github.kyant0:backdrop` (`lens()`) | rim-only |
| `FletchMcKee/liquid` (`refraction`) | rim-only |

**Lumen Glass** adds what they don't:

1. a **whole-face lens** (content bends across the entire surface, centre included),
2. a **tunable blend** between centre-weighted and rim-weighted models,
3. a **documented, first-class substrate** with the pitfalls encoded in the API (not left as
   gotchas),
4. **performance tiers** so it runs acceptably on low-end hardware.

We credit `backdrop` and `liquid` as **inspiration**; we do not depend on them.

---

## ⚠️ Read before writing any shader code

> **The shader must be clean-room.** See [`CLEANROOM.md`](CLEANROOM.md) and the licence constraint
> in [`docs/PLAN.md`](docs/PLAN.md) §3. No third-party shader text may be copied — the library must
> be publishable.

---

## Layout

```
LumenGlass/
├── library/                  # the library (single module for P1)
│   └── src/main/java/com/subhankar/lumenglass/
│       ├── core/             # platform-agnostic model + maths (NO android/androidx)
│       ├── android/          # RuntimeShader, capture, RenderEffect  (substrate = P2)
│       └── compose/          # public API: Modifier.lumenGlass(...)   (P4)
├── docs/
│   ├── PLAN.md               # full plan: gap, licence, decisions, roadmap, risks
│   └── ARCHITECTURE.md       # layout, purity rule, substrate, perf strategy
├── CLEANROOM.md              # shader provenance record (mandatory before publishing)
└── README.md
```

## Decisions at a glance

| # | Decision |
|---|---|
| **Q1** | Android-first, KMP-shaped — `core/` is platform-agnostic (no `androidx`). |
| **Q2** | **Own the substrate** — our own capture + `RenderEffect`; **no `backdrop` dependency**. |
| **Q3** | v1 effects: lens **blend**, dispersion, frost, tint, saturation/contrast, edge light, optional bezel. |
| **Perf** | First-class: tiers, reduced-res capture, separable blur; exit criterion = 60fps low-end. |

## Build

```bash
./gradlew :library:compileDebugKotlin
./gradlew :library:testDebugUnitTest
```

> Requires the Android SDK. Set `sdk.dir` in `local.properties` (not committed).

## License

_To be decided at publish time._ (See `docs/PLAN.md` §3 for the third-party attribution rules.)
