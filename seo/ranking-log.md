# Lumen Glass — search visibility log

Tracked by a scheduled SEO routine. Positions come from an AI web-search tool, **not Google**,
so treat them as "search visibility" (is the repo / site / article returned at all, and roughly
where), not as exact Google rank. "—" = not in the returned results (typically 7–30 results).

Targets counted as "Lumen Glass": the GitHub repo, the GitHub Pages site
(subhankarcodegeek.github.io/lumen-glass), JitPack page, or an article about the library.

| Date (IST) | Keyword | Found? | Position | Change | Ranking above / instead |
|---|---|---|---|---|---|
| 2026-11-04 | lumen glass github | No | — | baseline | dotLumen, tallys/lumen, exorev07/Lumen, nosey/lumen (unrelated "Lumen" repos) |
| 2026-11-04 | lumen glass | No | — | baseline | Lindner LUMEN glass floor panels, Crate & Barrel, furniture/lighting products |
| 2026-11-04 | lumen glass android | No | — | baseline | CF.lumen (Chainfire), Unreal Engine Lumen on Android |
| 2026-11-04 | glass effect in compose | No | — | baseline | dev.to quick glass effects, KMPLiquidGlass, AndroidLiquidGlass, ardakazanci/Glassmorphism |
| 2026-11-04 | jetpack compose glass effect | No | — | baseline | androidengineers substack, dev.to, ardakazanci, AndroidLiquidGlass, neilyich/glassmorphism-compose |
| 2026-11-04 | android glassmorphism compose | No | — | baseline | neilyich/glassmorphism-compose, substack, ProAndroidDev "Blurring the Lines" |
| 2026-11-04 | liquid glass android | No | — | baseline | QmDeve/AndroidLiquidGlassView (#1), news on Android 17 / Pixel liquid glass |
| 2026-11-04 | AGSL glass shader | No | — | baseline | abduzeedo "Crafting Glass", ProAndroidDev shaders post, developer.android.com AGSL |
| 2026-11-04 | compose blur glass library | No | — | baseline | Kashif-E/KMPLiquidGlass, skydoves/Cloudy |
| 2026-11-04 | android liquid glass | No | — | baseline | News on Android 17 / Pixel "liquid glass" rumours dominate; no libraries in results |
| 2026-11-04 | jetpack compose liquid glass | No | — | baseline | klibs.io (ienground/AndroidLiquidGlass, FletchMcKee/liquid, NadeemIqbal/liquid-glass), kotlinlang Slack release threads, Kashif-E/KMPLiquidGlass |
| 2026-11-04 | android liquid glass library | No | — | baseline | maxrave-dev/androidliquidglass, QmDeve/AndroidLiquidGlassView, Kyant0/AndroidLiquidGlass (klibs + docs) |
| 2026-11-04 | android glassmorphism | No | — | baseline | GeeksforGeeks, Medium, androidengineers substack, droidcon "Blurring the Lines", neilyich/glassmorphism-compose |
| 2026-11-04 | android backdrop blur | No | — | baseline | ezoushen/blur-cmp, AOSP window-blurs docs, ProAndroidDev dynamic blur, Dimezis/BlurView, Kyant backdrop docs |
| 2026-11-04 | compose glass library | No | — | baseline | Kashif-E/KMPLiquidGlass, maxrave-dev/androidliquidglass, NadeemIqbal dev.to post |
| 2026-11-04 | android glass effect | No | — | baseline | Expo glass-effect docs, KMPLiquidGlass, Wikipedia "Liquid Glass", QmDeve, dev.to AGSL React Native post |
| 2026-11-04 | glass UI android | No | — | baseline | Google Glass / Android XR docs (intent mismatch: smart-glasses), One UI 9 news |

Notes 2026-11-04: first logged run. The repo **is indexed** — an exact-match query
(`"lumen-glass" SubhankarCodeGeek android compose`) returns it at #1 — but it does not surface
for any target keyword yet. "Lumen" is a crowded brand term (Unreal Engine Lumen, CF.lumen,
Lindner LUMEN), so generic keywords and backlinks matter more than the brand name.

Notes 2026-11-04 (second run, 13:00 IST): added baselines for the 8 queries newly listed in the
routine prompt. Same method (AI web-search tool, US results, not Google). Not found for any.
Observations: klibs.io pages rank for most library-discovery queries, and kotlinlang Slack
release threads are indexed; "glass UI android" is dominated by smart-glasses intent and is a
poor target.

| Date (IST) | Keyword | Found? | Position | Change | Ranking above / instead |
|---|---|---|---|---|---|
| 2026-11-05 | lumen glass github android | No | — | = (not found) | pub.dev lumen_kit, ecosyste.ms (openlumen, inkapplications/glass), Arm "Lumen HWRT on Android", CF.lumen |
| 2026-11-05 | jetpack compose liquid glass library | No | — | = (not found) | QWEA0/Liquid-Glass-Android wiki, maxrave-dev/androidliquidglass, klibs.io ienground/AndroidLiquidGlass, kotlinlang Slack release threads, Mortd3kay/liquid-glass-android |
| 2026-11-05 | android glassmorphism jetpack compose | No | — | = (not found) | **new:** saadkhalidkhan/compose-glasskit (Maven Central); klibs.io neilyich/glassmorphism-compose, ProAndroidDev/droidcon, substack, hashnode, Medium |

Notes 2026-11-05: spot check only (3 queries; full 17-query sweep is weekly and last ran
2026-11-04). Same method (AI web-search tool, US results, not Google). No movement — expected,
since no new indexable content shipped between runs. New competitor on Maven Central:
`io.github.saadkhalidkhan:compose-glasskit`. Pattern worth noting: klibs.io and Maven Central
listings rank for library-discovery queries; Lumen Glass is JitPack-only and absent from both.
Search Console data still not accessible to the routine.
