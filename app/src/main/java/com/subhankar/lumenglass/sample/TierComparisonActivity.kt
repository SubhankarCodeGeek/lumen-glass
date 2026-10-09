package com.subhankar.lumenglass.sample

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RecordingCanvas
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.subhankar.lumenglass.android.GlassCapture
import com.subhankar.lumenglass.android.GlassUniforms
import com.subhankar.lumenglass.android.TIER_BALANCED
import com.subhankar.lumenglass.android.TIER_HIGH
import com.subhankar.lumenglass.android.applyGlassEffect
import com.subhankar.lumenglass.core.Bezel
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.LensModel
import com.subhankar.lumenglass.core.QualityTier
import com.subhankar.lumenglass.core.resolveFeatures
import com.subhankar.lumenglass.core.selectTier

/**
 * P3.5 harness — **tier comparison + performance HUD**.
 *
 * ## Why this screen exists
 *
 * P3.5's exit criterion is a *frame-rate* target, and `PLAN.md` §5.2's tier policy is only real if a
 * caller can see and force it. This screen does both:
 *
 * - cycles the four tiers so their **visual** cost can be compared on device, and
 * - shows the **resolved features and live frame timings**, because the criterion cannot be judged
 *   without them.
 *
 * ## What it exercises
 *
 * Each tier renders through the library's own substrate with that tier's [GlassFeatures] applied:
 * `captureScale` on the capture, `useBlur` as a chained `RenderEffect` blur, and `useBezel` /
 * `useDispersion` via the shader's `tier` uniform. So this is the phase's whole policy made
 * executable, not a mock of it.
 *
 * ## Honest scope note
 *
 * Frame timings measured here are on whatever device runs it. `PLAN.md` §6.1 targets a *flagship*
 * and a *low-end* reference; a mid-range device (such as the Motorola Edge 70 this was built
 * against) satisfies neither, so its numbers are informative but do not discharge the criterion.
 */
class TierComparisonActivity : ComponentActivity() {

    private lateinit var root: LinearLayout
    private lateinit var hud: TextView
    private lateinit var surface: TierSurfaceView
    private lateinit var tierLabel: TextView

    private val frameTiming = FrameTiming()

    /** The tier the previous tap selected; `null` = start from the policy default. */
    private var requestedTier: QualityTier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.subhankar.lumenglass.android.LumenGlassDebug.enabled = true

        // Honour a requested tier from the launch intent, so tiers can be driven deterministically
        // from the command line / a test:
        //   adb shell am start -n <activity> --es tier HIGH
        // This exists because tapping through the UI is unreliable to script, and a verification
        // path that depends on synthetic taps is a verification path that breaks for the wrong
        // reasons. The tap-to-cycle remains for interactive use.
        requestedTier = intent?.getStringExtra(EXTRA_TIER)?.let { name ->
            QualityTier.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AndroidColor.BLACK)
        }

        tierLabel = label("").apply { setOnClickListener { cycleTier() } }
        hud = label("frames: measuring…")

        root.addView(label("P3.5 · tap the tier line to cycle · watch the HUD"))
        root.addView(tierLabel)
        root.addView(hud)

        surface = TierSurfaceView()
        root.addView(
            surface,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                /* weight = */ 1f,
            ),
        )

        setContentView(root)

        // Ask the platform for a high frame rate.
        //
        // Why this is here: this device pins the app's UID to 45Hz via a system governor
        // (`frameRateOverride {uid=... frameRateHz=45.0}`), which made every tier measure the same
        // and rendered the in-app frame-timing HUD useless for P3.5. `setFrameRate` is the
        // documented way for an app to *request* a rate. It is a request, not a guarantee — a
        // system governor can still override it — and checking whether it yields is the whole point
        // of this code being here. See docs/PERF.md.
        requestHighFrameRate()

        // Populate the tier label and hand the surface its starting tier. Without this the screen
        // renders the default tier but the label stays blank, so the user cannot tell what they are
        // looking at — which defeats the point of a comparison screen.
        applyTier()
    }

    /**
     * Windows-level requests to favour frame rate over power saving.
     *
     * ## Tested and ineffective on this device (kept deliberately)
     *
     * This device pins the app's UID to 45Hz via a system power governor
     * (`frameRateOverride {uid=... frameRateHz=45.0}`), which makes every tier measure identically
     * and rendered the in-app frame-timing HUD useless for P3.5.
     *
     * These two calls are the only window-level frame-rate levers the platform exposes to a plain
     * `View` hierarchy (`Surface.setFrameRate` needs a `Surface`, which only a `SurfaceView` owns).
     * They were tried as an experiment: the governor **re-applied 45Hz anyway**, including to a
     * freshly-installed UID. See `docs/PERF.md`.
     *
     * The calls are kept rather than deleted so the negative result is reproducible and so nobody
     * re-runs the same experiment assuming it was never tried. They are harmless where no governor
     * is present — indeed that is exactly where they would help.
     */
    private fun requestHighFrameRate() {
        val w = window ?: return
        w.setFrameRateBoostOnTouchEnabled(true)
        w.setFrameRatePowerSavingsBalanced(false)
    }

    override fun onResume() {
        super.onResume()
        frameTiming.start()
        hudUpdater.post(hudTick)
    }

    override fun onPause() {
        super.onPause()
        frameTiming.stop()
        hudUpdater.removeCallbacks(hudTick)
    }

    private val hudUpdater = android.os.Handler(android.os.Looper.getMainLooper())
    private val hudTick = object : Runnable {
        override fun run() {
            hud.text = frameTiming.summary()
            hudUpdater.postDelayed(this, HUD_INTERVAL_MS)
        }
    }

    /**
     * Cycles AUTO → HIGH → BALANCED → LOW → FALLBACK → AUTO.
     *
     * AUTO goes through [selectTier] with no override, so the probe's real decision is visible
     * rather than only the four explicit tiers.
     */
    private fun cycleTier() {
        requestedTier = when (requestedTier) {
            null -> QualityTier.HIGH
            QualityTier.HIGH -> QualityTier.BALANCED
            QualityTier.BALANCED -> QualityTier.LOW
            QualityTier.LOW -> QualityTier.FALLBACK
            QualityTier.FALLBACK -> null
        }
        applyTier()
    }

    private fun applyTier() {
        val apiLevel = Build.VERSION.SDK_INT
        val effective = selectTier(apiLevel, requestedTier)

        // The detectability contract: show what was ASKED for and what was actually granted. If a
        // request could not be honoured, the difference is visible here rather than silent.
        val asked = requestedTier?.name ?: "AUTO"
        val clamped = requestedTier != null && effective != requestedTier
        tierLabel.text = buildString {
            append("tier: $asked -> ${effective.name}")
            if (clamped) append("  (CLAMPED: device cannot run $asked)")
            append("   [tap to cycle]")
        }

        surface.setTier(effective)
        frameTiming.reset()
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(AndroidColor.WHITE)
        textSize = 13f
        setPadding(24, 16, 24, 8)
    }

    /**
     * Renders the glass at a given tier through the library's own capture + effect path.
     */
    private inner class TierSurfaceView : View(this@TierComparisonActivity) {

        private var tier: QualityTier = QualityTier.BALANCED
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var capture: GlassCapture? = null

        /**
         * Continuous animation phase. **The measurement is meaningless without it.**
         *
         * An earlier version of this harness drew a static frame and reported identical frame
         * timings (~45fps) for FALLBACK and HIGH alike — because a static screen schedules no new
         * frames, so the sampler was mostly measuring idle vsync, not render cost. The exit
         * criterion is explicitly about glass *animating* over moving content, so the surface must
         * keep requesting frames or there is nothing to measure.
         */
        private var phase = 0f

        private val animator = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                phase += ANIMATION_STEP
                if (phase > 1f) phase -= 1f
                invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            Choreographer.getInstance().postFrameCallback(animator)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            Choreographer.getInstance().removeFrameCallback(animator)
            capture?.release()
            capture = null
        }

        fun setTier(newTier: QualityTier) {
            tier = newTier
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            capture?.release()
            capture = GlassCapture()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val features = tier.resolveFeatures()

            if (!features.useShader) {
                // FALLBACK — a clean frosted panel. PLAN §5.2: must look INTENTIONAL, not broken.
                // This is also the negative control for `useShader`: if the lens still appeared
                // here, the tier gate would be doing nothing.
                paint.shader = null
                paint.color = 0xB0FFFFFF.toInt()
                canvas.drawRect(0f, 0f, w, h, paint)
                return
            }

            // Content the glass will refract, shifted by the animation phase so the surface is
            // genuinely re-rendering every frame (see the `phase` note above).
            val shift = phase * w
            canvas.save()
            canvas.translate(-shift, 0f)
            HighContrastContent.draw(canvas, w * 2f, h)
            canvas.restore()

            val active = capture ?: return
            val spec = GlassSpec(
                lens = LensModel.Blend(centre = 0.6f, rim = 0.4f),
                refraction = 1.2f,
                // Dispersion is tier-gated: the shader ignores it below HIGH.
                dispersion = 0.35f,
                frostDp = 6f,
                tintArgb = 0x2200E5FF,
                edgeLight = 0.1f,
                bezel = Bezel.soft(radiusDp = 12f, alpha = 0.35f),
            )

            val uniforms = GlassUniforms.from(
                spec = spec,
                widthPx = w,
                heightPx = h,
                cornerRadiusPx = 24f,
                tier = tier.uniformValue(),
                bezelRadiusPx = 18f,
            )

            // Record at THIS TIER'S capture scale, then render through the lens (with blur when the
            // tier asks for it). `captureScale` and `useBlur` are the two levers P3.5 adds.
            active.record(0f, 0f, w, h, scale = features.captureScale) { recordCanvas ->
                recordCanvas.save()
                recordCanvas.translate(-shift, 0f)
                HighContrastContent.draw(recordCanvas, w * 2f, h)
                recordCanvas.restore()
            }

            val frostPx = if (features.useBlur) dpToPx(spec.frostDp) else 0f
            val applied = applyGlassEffect(active, uniforms, frostPx)
            if (!applied) {
                paint.shader = null
                paint.color = 0xB0FFFFFF.toInt()
                canvas.drawRect(0f, 0f, w, h, paint)
                return
            }

            // Draw the capture through its node. The node carries the effect chain (blur -> lens).
            //
            // `RenderNode` has no public `render(Canvas)` — the supported route is the parent
            // canvas drawing the node, via `RecordingCanvas.drawRenderNode`. A hardware-accelerated
            // view's onDraw canvas IS a RecordingCanvas, which is why this cast is the documented
            // pattern rather than a hack.
            (canvas as? RecordingCanvas)?.drawRenderNode(active.node)
        }

        private fun QualityTier.uniformValue(): Float = when (this) {
            QualityTier.HIGH -> TIER_HIGH
            // LOW and BALANCED share the cheap single-sample shader path; LOW differs by running no
            // blur and a smaller capture, both applied above rather than in the shader.
            QualityTier.BALANCED -> TIER_BALANCED
            QualityTier.LOW -> TIER_BALANCED
            QualityTier.FALLBACK -> TIER_BALANCED
        }

        private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
    }

    private companion object {
        const val HUD_INTERVAL_MS = 500L

        /** Fraction of the surface width the content advances per frame. */
        const val ANIMATION_STEP = 0.01f

        /** Launch extra: force a tier by name, e.g. `--es tier HIGH`. */
        const val EXTRA_TIER = "tier"
    }
}
