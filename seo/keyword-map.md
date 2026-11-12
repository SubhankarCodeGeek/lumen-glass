# Lumen Glass — keyword-to-URL map

One search intent → one target URL, so pages don't compete with each other.
Status: **Targeted** (page exists and is written for this intent), **Partial** (mentioned, not the
page's focus), **Gap** (no page yet). Search-demand and difficulty figures are not recorded here:
no verified source (Search Console / keyword tool) is connected yet. Do not add guessed numbers.

Base: `https://subhankarcodegeek.github.io/lumen-glass/` (site) · `github.com/SubhankarCodeGeek/lumen-glass` (repo)

| Cluster | Keyword | Intent | Target URL | Status | Notes |
|---|---|---|---|---|---|
| Primary | jetpack compose liquid glass | Library / how-to | `/` (landing) | Partial | Landing title already leads with "Liquid Glass for Android & Jetpack Compose" |
| Primary | android liquid glass | Mixed (news + dev) | `/` | Partial | SERP is news-heavy (Android 17 rumours); win via library-discovery variants first |
| Primary | liquid glass android | Mixed | `/` | Partial | Same intent as above — do not create a separate page |
| Primary | jetpack compose glass effect | How-to | `/tutorials/glass-effect-jetpack-compose/` | Targeted | Tutorial drafted 2026-11-12 (branch `seo/compose-glass-tutorial-2026-11-12`); README FAQ links to it |
| How-to | jetpack compose glass bottom navigation bar / glass navigation bar compose | How-to | `/tutorials/glass-effect-jetpack-compose/` | Targeted | Same page; the bottom bar is the worked example. Baseline logged 2026-11-12 |
| Primary | android glassmorphism | How-to / concept | future `/tutorials/glassmorphism-jetpack-compose/` | Gap | SERP = tutorials (GfG, Medium, droidcon) — needs a tutorial, not the landing page |
| Primary | android glass effect | Mixed | `/` | Partial | |
| Primary | glass UI android | Smart-glasses (mismatch) | — | Skip | SERP is Google Glass / Android XR; low relevance |
| Technical | AGSL glass shader | Learn / implement | `/docs/ARCHITECTURE.html` → future `/tutorials/agsl-glass-shader/` | Partial | Architecture doc covers the shader but is written as an internal design doc |
| Technical | android runtimeshader glass effect | Implement | same as AGSL glass shader | Partial | Merge with AGSL page |
| Technical | android backdrop blur | Implement | future `/comparisons/liquid-glass-vs-blur/` | Gap | SERP = blur libs + AOSP docs; a comparison page fits better than claiming the term |
| Technical | compose blur effect | Implement | same as above | Gap | |
| Performance | android glass rendering performance | Evaluate | `/docs/PERF.html` | Targeted | Dev-facing summary + title/description added 2026-11-09 (branch `seo/docs-metadata-2026-11-09`) |
| Library | android liquid glass library | Find a library | `/` + repo README | Partial | Competitors rank via klibs.io and GitHub |
| Library | compose glass library | Find a library | repo README | Partial | |
| Library | android glassmorphism library | Find a library | repo README | Partial | |
| Install | lumen glass installation / jitpack / getting started | Install | `/docs/getting-started/` | Targeted | Added 2026-11-05 (PR branch seo/getting-started-2026-11-05) |
| How-to | how to add liquid glass in jetpack compose | How-to | `/tutorials/glass-effect-jetpack-compose/` | Targeted | Handed over 2026-11-12; `/docs/getting-started/` keeps the install/setup intent and links to the tutorial |
| Branded | lumen glass / lumen glass github / lumen glass android | Navigational | `/` and repo | Targeted | Not yet surfacing — "Lumen" is crowded (Unreal Lumen, CF.lumen, Lindner, and since 2026-11-12 aldefy/Lumen, a Compose coachmark library). Always use the two-word name |
