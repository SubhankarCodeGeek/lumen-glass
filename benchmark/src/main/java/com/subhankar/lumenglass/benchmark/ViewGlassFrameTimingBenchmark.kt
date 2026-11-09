package com.subhankar.lumenglass.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for the **View/XML** surface — the `LumenGlassView` counterpart of
 * [GlassFrameTimingBenchmark].
 *
 * ## What it measures
 *
 * `ViewGlassActivity` inflates one `LumenGlassSourceLayout` containing a scrolling `ScrollView` and
 * two `LumenGlassView` surfaces over it. Each swipe scrolls the source, which invalidates it, which
 * re-records the capture and re-runs the shader for both surfaces. That is steady-state View-path
 * cost, which is what the tier policy is trading against.
 *
 * ## Why it is a separate class
 *
 * The Compose benchmark drives `TierComparisonActivity` and can select a tier through an intent
 * extra. The View harness deliberately has **no tier knob**: its layout is the documented XML
 * configuration, and a pixel test proves that configuration renders. Measuring the configuration
 * as shipped is the honest comparison; a benchmark-only tier switch would measure something no
 * consumer writes.
 *
 * ## Read it with `PERF.md`
 *
 * The same device caveats apply: frame timing here is whatever the device allows, and this app's
 * UID is pinned to 45 Hz on the reference device. The harness is reproducible evidence-gathering,
 * not a 60 fps claim.
 *
 * ```
 * ./gradlew :benchmark:connectedBenchmarkAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.subhankar.lumenglass.benchmark.ViewGlassFrameTimingBenchmark
 * ```
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ViewGlassFrameTimingBenchmark {

    @get:Rule
    val benchmarkRule: MacrobenchmarkRule = MacrobenchmarkRule()

    @Test
    fun frameTimingViewSurfaces() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
        ) {
            startActivityAndWait(
                intent = Intent().setClassName(TARGET_PACKAGE, VIEW_ACTIVITY),
            )

            // Wait for the inflated hierarchy before measuring, so launch work is excluded.
            device.wait(Until.hasObject(By.textContains("LUMEN GLASS")), TIMEOUT_MS)

            // Keep scrolling so the source invalidates and both surfaces redraw every frame.
            // A static screen would schedule no new frames and FrameTimingMetric would have
            // nothing to report.
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

    private companion object {
        const val TARGET_PACKAGE = "com.subhankar.lumenglass.sample"
        const val VIEW_ACTIVITY = "$TARGET_PACKAGE.ViewGlassActivity"
        const val ITERATIONS = 5
        const val ANIMATION_SWIPES = 6
        const val STEPS = 20
        const val TIMEOUT_MS = 5_000L
    }
}
