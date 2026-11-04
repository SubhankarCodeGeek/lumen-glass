# Lumen Glass — content roadmap

Priority uses the routine's P0–P3 scale. Each page must carry real code, real screenshots from
this repo and links to source; no thin keyword pages.

| Pri | Page | Target cluster (see keyword-map.md) | Status | Depends on |
|---|---|---|---|---|
| P1 | `/docs/getting-started/` — install (JitPack), minimal `Modifier` example, API-level requirements, fallback behaviour | Install, branded | Planned | Confirm min API / fallback from library source |
| P1 | `/tutorials/glass-effect-jetpack-compose/` — build a floating glass card step by step | jetpack compose glass effect, liquid glass | Planned | Getting-started page |
| P2 | `/tutorials/agsl-glass-shader/` — how the refraction/rim shader works, with excerpts from the AGSL source | AGSL glass shader, RuntimeShader | Planned | Reuse ARCHITECTURE.md material, rewritten for readers |
| P2 | `/comparisons/liquid-glass-vs-blur/` — what a blur (RenderEffect/BlurView) does vs refraction, with side-by-side screenshots | backdrop blur, glassmorphism | Planned | Screenshots; neutral wording, no unsupported superiority claims |
| P2 | `/tutorials/glassmorphism-jetpack-compose/` | android glassmorphism | Idea | Could merge with the glass-effect tutorial if intents overlap |
| P2 | Developer-facing intro on `/docs/PERF.html` | performance | Idea | |

Site-hygiene items (not pages):
- sitemap.xml has no `<lastmod>`; `PLAN.md` / `PARITY.md` are internal planning docs that are in the sitemap — decide whether they should be public/indexed.
- README "Why Lumen Glass?" says other implementations are "shallow wrappers … blacked-out rims"; soften or back with evidence (routine rule: no unsupported superiority claims).
