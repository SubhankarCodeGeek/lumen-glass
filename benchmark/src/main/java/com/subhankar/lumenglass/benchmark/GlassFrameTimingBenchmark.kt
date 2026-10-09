package com.subhankar.lumenglass.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
// NOTE: the JUnit4 rule lives in `...macro.junit4`, NOT `...macro`. The wrong import produces
// "unresolved reference" on every member of the measureRepeated lambda, which looks like an API
// mismatch but is simply the wrong package.
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame-timing benchmark for the Lumen Glass tier policy — the evidence for `PLAN.md` §6.1.
 *
 * ## Why this exists instead of the in-app HUD
 *
 * The P3.5 harness had a hand-rolled `Choreographer` sampler. It reported ~45.5fps for *every*
 * tier — FALLBACK and HIGH alike — because the device pins this app's UID to 45Hz:
 *
 * ```
 * FrameRateOverrides=(uid, frameRate)={10454, 45.00 Hz}
 * ```
 *
 * That sampler was measuring the power governor, not the shader. `PLAN.md` §6.1 already prescribed
 * the fix: *"Measure with **Macrobenchmark** + GPU frame timing in the sample app."* This is that.
 *
 * What Macrobenchmark does differently: `FrameTimingMetric` reads platform frame data rather than
 * sampling vsync callbacks by hand; `CompilationMode` removes JIT/interpretation noise; each
 * iteration is isolated and repeated; and results come back as percentiles.
 *
 * ## Why FALLBACK is measured too
 *
 * **FALLBACK draws no shader at all.** It is the *baseline that establishes the floor this device
 * imposes*. Without it there is no way to distinguish our shader's cost from the device's ceiling:
 * if HIGH and FALLBACK come out the same, the measurement is still dominated by the governor and
 * the tier numbers say nothing about our rendering.
 *
 * This is the benchmark's own negative control, and `PERF.md` must be read with it in mind.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :benchmark:connectedBenchmarkAndroidTest
 * ```
 *
 * Results land in `benchmark/build/outputs/connected_android_test_additional_output/`.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class GlassFrameTimingBenchmark {

    @get:Rule
    val benchmarkRule: MacrobenchmarkRule = MacrobenchmarkRule()

    /**
     * Drives the tier-comparison screen for one tier and measures frame timing while the content
     * moves underneath the glass.
     *
     * The interaction is a repeatable swipe pattern rather than a scroll gesture API, because the
     * tier surface animates its own content and we only need to keep the device producing frames
     * under the glass — the point is the glass's cost, not the scroll's.
     *
     * `StartupMode.WARM` measures steady-state rendering rather than cold start, which is what the
     * criterion is about ("glass animating over scrolling content").
     */
    private fun benchmarkTier(tier: String) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            // Partial compilation pre-compiles the hot path so we are not measuring the
            // interpreter. `None` would be optimistic; `Full` overstates a real user's day-one
            // experience. Partial is the standard middle ground.
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
        ) {
            startActivityAndWait(
                // The harness accepts `--es tier <NAME>` (added in P3.5 so tiers could be driven
                // deterministically instead of by synthetic taps).
                intent = android.content.Intent()
                    .setClassName(TARGET_PACKAGE, "$TARGET_PACKAGE.TierComparisonActivity")
                    .putExtra("tier", tier),
            )

            // Wait for the tier label so we are measuring after the screen has actually settled,
            // not during launch.
            device.wait(Until.hasObject(By.textContains("tier:")), TIMEOUT_MS)

            // Keep the device rendering frames. Without movement a static screen schedules no new
            // frames and FrameTimingMetric would have nothing to report — the same trap the
            // in-app sampler fell into.
            repeat(ANIMATION_SWIPES) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight / 2,
                    device.displayWidth / 2,
                    device.displayHeight / 3,
                    STEPS,
                )
            }
        }
    }

    @Test
    fun frameTimingFallbackBaseline() = benchmarkTier("FALLBACK")

    @Test
    fun frameTimingLow() = benchmarkTier("LOW")

    @Test
    fun frameTimingBalanced() = benchmarkTier("BALANCED")

    @Test
    fun frameTimingHigh() = benchmarkTier("HIGH")

    private companion object {
        const val TARGET_PACKAGE = "com.subhankar.lumenglass.sample"
        const val ITERATIONS = 5
        const val ANIMATION_SWIPES = 6
        const val STEPS = 20
        const val TIMEOUT_MS = 5_000L
    }
}
