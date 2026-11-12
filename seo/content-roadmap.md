# Lumen Glass — content roadmap

Priority uses the routine's P0–P3 scale. Each page must carry real code, real screenshots from
this repo and links to source; no thin keyword pages.

| Pri | Page | Target cluster (see keyword-map.md) | Status | Depends on |
|---|---|---|---|---|
| P1 | `/docs/getting-started/` — install (JitPack), minimal `Modifier` example, API-level requirements, fallback behaviour | Install, branded | Drafted 2026-11-05 (branch `seo/getting-started-2026-11-05`) | Verified against source: minSdk 21, AGSL ≥ 33, default tier BALANCED |
| P1 | `/tutorials/glass-effect-jetpack-compose/` — liquid glass bottom navigation bar over a scrolling list, step by step | jetpack compose glass effect, glass bottom bar | Drafted 2026-11-12 (branch `seo/compose-glass-tutorial-2026-11-12`) | Code mirrors the sample app's "Bottom Dock" mode; not compiled in CI (no Android SDK in the routine) — owner should paste-test once |
| P2 | `/tutorials/agsl-glass-shader/` — how the refraction/rim shader works, with excerpts from the AGSL source | AGSL glass shader, RuntimeShader | Planned | Reuse ARCHITECTURE.md material, rewritten for readers |
| P2 | `/comparisons/liquid-glass-vs-blur/` — what a blur (RenderEffect/BlurView) does vs refraction, with side-by-side screenshots | backdrop blur, glassmorphism | Planned | Screenshots; neutral wording, no unsupported superiority claims |
| P2 | `/tutorials/glassmorphism-jetpack-compose/` | android glassmorphism | Idea | Could merge with the glass-effect tutorial if intents overlap |
| P2 | Developer-facing intro on `/docs/PERF.html` | performance | Drafted 2026-11-09 (branch `seo/docs-metadata-2026-11-09`) | Summary uses only figures already in PERF.md; one device, 45Hz cap stated |

Site-hygiene items (not pages):
- ~~sitemap.xml has no `<lastmod>`~~ (fixed 2026-11-09 branch); `PLAN.md` / `PARITY.md` are internal planning docs that are in the sitemap — decide whether they should be public/indexed.
- README "Why Lumen Glass?" says other implementations are "shallow wrappers … blacked-out rims"; soften or back with evidence (routine rule: no unsupported superiority claims).

Found 2026-11-05 (fixed on branch `seo/getting-started-2026-11-05`):
- README Quick Start and both recipes used `highlightStroke`, which does not exist in `GlassSpecBuilder` (never in git history) — copy-pasted code failed to compile. Replaced with `edgeLight`.
- README knobs table defaults disagreed with source (dispersion 0.06 vs 0f, lens Blend(0.4,0.6) vs (0.6,0.4), tint Transparent vs Unspecified, bezel on vs null). Corrected; added note that dispersion/bezel need `QualityTier.HIGH`.
- Docs layout: every docs page had a filename-derived title ("Perf") and the same templated description. Layout now honours `seo_title` / `description` / `crumb` front matter (existing pages unchanged until given front matter).

Next candidates:
- ~~Give ARCHITECTURE / PERF / CLEANROOM front-matter titles + descriptions (P2, small).~~ Drafted 2026-11-09, branch `seo/docs-metadata-2026-11-09`; sitemap now carries `<lastmod>` (from git commit dates).
- Decide whether PLAN.md / PARITY.md stay indexed (owner decision).
- Consider Maven Central publication: klibs.io and Maven Central listings rank for library-discovery queries (owner decision; not SEO-only work).

Found 2026-11-09:
- Repository topics: `github.com/topics/liquid-glass` and `/topics/agsl` appear in search results for library queries. Confirm the repo carries `liquid-glass`, `agsl`, `jetpack-compose`, `glassmorphism`, `android-library` (owner action — routine could not read repo settings: GitHub API not enabled for the session).
- ARCHITECTURE.md opens with internal context (sibling folder next to `360 Community`, `../LumenGlass/`) that is confusing for public readers; consider a public-facing opening paragraph (P2).

Found 2026-11-12:
- `scripts/validate_site.py` only globbed `*.html` and `docs/*.html`, so pages with pretty
  permalinks (`/docs/getting-started/`, `/tutorials/...`) were never validated. Now uses `rglob`
  (fixed on branch `seo/compose-glass-tutorial-2026-11-12`). It surfaces one more pre-existing
  warning: the getting-started `<title>` is 76 characters.
- Docs `<title>` suffix " | Lumen Glass documentation" (28 chars) pushes 5 pages over 70 characters.
  Consider " | Lumen Glass" site-wide (P3, small; changes titles of already-indexed pages, so batch it
  with other metadata work).
- Library API note for the docs (not SEO work): a `LumenGlassState` tracks one glass surface's
  position, so two `Modifier.lumenGlass` surfaces sharing a state would sample from the wrong
  offset. The tutorial states this as a 0.2.0 limitation; the owner may want to fix or document it
  in the README too.
- Brand collision: aldefy/Lumen (Compose coachmarks, Maven Central `lumen`). Keep "Lumen Glass"
  as two words everywhere.
