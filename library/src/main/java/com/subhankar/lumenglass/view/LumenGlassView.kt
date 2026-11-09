/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View.NO_ID
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.IdRes
import com.subhankar.lumenglass.android.AGSL_MIN_API
import com.subhankar.lumenglass.android.LumenGlassDebug
import com.subhankar.lumenglass.android.OverlapRect
import com.subhankar.lumenglass.android.TAG
import com.subhankar.lumenglass.android.checkOverlap
import com.subhankar.lumenglass.android.logOverlapVerdict
import com.subhankar.lumenglass.android.resolveGlassRenderPlan
import com.subhankar.lumenglass.core.GlassFeatures
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.QualityTier
import com.subhankar.lumenglass.core.resolveFeatures
import com.subhankar.lumenglass.core.selectTier

/**
 * A glass surface for the Android View system.
 *
 * It samples a [LumenGlassSourceLayout] — the **only** accepted source — and draws a refracted,
 * blurred and tinted copy of it inside its own bounds. Children added to the view draw on top of
 * the glass, at their normal z-order, so a surface can host its own labels and controls.
 *
 * ## XML
 *
 * ```xml
 * <!-- The source declares the content; the glass declares the material. -->
 * <com.subhankar.lumenglass.view.LumenGlassSourceLayout
 *     android:id="@+id/backdrop"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent" />
 *
 * <com.subhankar.lumenglass.view.LumenGlassView
 *     android:layout_width="280dp"
 *     android:layout_height="160dp"
 *     app:lumenGlassSource="@id/backdrop"
 *     app:lumenGlassCornerRadius="24dp"
 *     app:lumenGlassFrost="8dp"
 *     app:lumenGlassQuality="balanced" />
 * ```
 *
 * Declare the glass **after** the source it samples. The source records its content during its own
 * draw; a glass drawn first samples the previous frame and is refreshed on the next one, so the
 * result is one frame stale rather than wrong.
 *
 * ## Kotlin
 *
 * ```kotlin
 * val glass = LumenGlassView(context)
 * glass.setSource(sourceLayout)
 * glass.setGlassConfig {
 *     lens = LensModel.Blend(centre = 0.6f, rim = 0.4f)
 *     cornerRadiusDp = 24f
 *     frostDp = 8f
 * }
 * ```
 *
 * ## Platform requirements
 *
 * The glass path needs **API 33** (AGSL) and a **hardware-accelerated** canvas. Below that — and
 * when no source is bound, or the source has not drawn yet — the surface draws the shared FROST
 * panel instead of the shader. That fallback is deliberate and shared with the Compose backend; it
 * never silently disappears.
 *
 * ## Limitations (see `docs/VIEW.md`)
 *
 * - Only a [LumenGlassSourceLayout] can be sampled. An arbitrary sibling cannot: `View.draw()`
 *   into another node loses the parent transform, alpha and clip, and double-rasterises.
 * - `SurfaceView` and `TextureView` content is not in the View draw hierarchy and cannot be
 *   sampled.
 * - A source in a different window (a dialog, another activity) has no meaningful offset and falls
 *   back to the frost panel.
 * - Transitions that move a view without invalidating it are not tracked beyond the view's own
 *   layout and scroll notifications.
 *
 * ## Threading and lifecycle
 *
 * UI thread only. The view owns a native capture while it is attached; [onDetachedFromWindow]
 * releases it, and the next draw after re-attach recreates it. [release] is idempotent.
 */
@SuppressLint("NewApi") // Every API 29/33 use is behind an explicit version guard; see the file.
public class LumenGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var spec: GlassSpec = GlassSpec.Default
    private var enabled: Boolean = true
    private var qualityOverride: QualityTier? = null
    private var boundSource: LumenGlassSourceLayout? = null
    private var sourceResourceId: Int = NO_ID

    /** Created lazily on the first hardware draw at API 33+. */
    private var renderer: ViewGlassRenderer? = null

    private val frostPanel = GlassFrostPanel()

    private val windowLocation = IntArray(2)
    private var lastWindowX: Int = Int.MIN_VALUE
    private var lastWindowY: Int = Int.MIN_VALUE

    /**
     * Re-records the glass when the view tree scrolls, so the sampled offset stays correct when the
     * source and the surface move independently (for example a fixed surface over a scrolling
     * list).
     */
    private val scrollInvalidator = ViewTreeObserver.OnScrollChangedListener { invalidate() }

    init {
        // ViewGroup defaults to WILL_NOT_DRAW; without clearing it `onDraw` never runs and the
        // glass is invisible while everything else appears to work.
        setWillNotDraw(false)

        if (attrs != null) {
            val parsed = LumenGlassAttrs.read(context, attrs, defStyleAttr)
            spec = parsed.values.toGlassSpec()
            enabled = parsed.enabled
            qualityOverride = parsed.quality
            sourceResourceId = parsed.sourceId ?: NO_ID
        }
    }

    /** The material currently configured. */
    public val glassSpec: GlassSpec get() = spec

    /** The source this surface samples, or `null` when unbound. */
    public val source: LumenGlassSourceLayout? get() = boundSource

    /** Whether the surface renders glass (as opposed to a plain, transparent layout). */
    public val isGlassEnabled: Boolean get() = enabled

    /**
     * The tier actually in effect after capability selection and any override.
     *
     * Compare this against [setQualityOverride]'s argument to detect a downgrade — the detectability
     * contract shared with the Compose API.
     */
    public val resolvedTier: QualityTier
        get() = selectTier(apiLevel = Build.VERSION.SDK_INT, requestedTier = qualityOverride)

    /** The concrete effect set [resolvedTier] enables. */
    public val resolvedFeatures: GlassFeatures get() = resolvedTier.resolveFeatures()

    /** `true` when this device can run the AGSL shader (API 33+). */
    public val canRunShader: Boolean get() = resolvedFeatures.useShader

    /**
     * Replaces the material in one call.
     *
     * ```kotlin
     * setGlassConfig {
     *     lens = LensModel.Blend(centre = 0.6f, rim = 0.4f)
     *     cornerRadiusDp = 20f
     *     bezel(radiusDp = 10f, alpha = 0.14f)
     * }
     * ```
     */
    public fun setGlassConfig(block: LumenGlassConfigBuilder.() -> Unit) {
        val builder = LumenGlassConfigBuilder()
        builder.block()
        setGlassSpec(builder.build())
    }

    /** Replaces the material with an already-built spec. */
    public fun setGlassSpec(spec: GlassSpec) {
        if (this.spec == spec) return
        this.spec = spec
        invalidateGlass()
    }

    /** `false` turns this into a plain, transparent [FrameLayout]. */
    public fun setGlassEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        invalidate()
    }

    /**
     * Forces a tier instead of letting the capability probe choose.
     *
     * The override is clamped to what the device can run, exactly as in the Compose API; read
     * [resolvedTier] to see what was actually applied. `null` restores capability selection.
     */
    public fun setQualityOverride(tier: QualityTier?) {
        if (qualityOverride == tier) return
        qualityOverride = tier
        invalidateGlass()
    }

    /**
     * Binds the source to sample, or unbinds with `null`.
     *
     * Passing any `View` other than a [LumenGlassSourceLayout] is a compile error; XML references
     * are validated at resolve time and rejected with a debug-logged reason.
     */
    public fun setSource(source: LumenGlassSourceLayout?) {
        sourceResourceId = NO_ID
        if (source == null) {
            unbindSource()
            invalidate()
            return
        }
        bindSource(source)
    }

    /** Resolves `sourceId` against this view's tree and binds the matching source. */
    public fun setSource(@IdRes sourceId: Int) {
        unbindSource()
        sourceResourceId = sourceId
        tryBindSource()
        invalidate()
    }

    /**
     * Drops the cached capture and effect and redraws.
     *
     * Use after changing anything the material depends on outside this view (for example a source
     * that has just been populated). Normal configuration calls already do this.
     */
    public fun invalidateGlass() {
        releaseRenderer()
        invalidate()
    }

    /**
     * Frees the native capture and cached effect.
     *
     * Idempotent. If the view is still on screen the next draw recreates them, so this is safe to
     * call from memory-pressure handlers; it is not a permanent shutdown.
     */
    public fun release() {
        releaseRenderer()
        invalidate()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // The source usually precedes the glass in the layout, so it is already in the tree here.
        // If it is not, onAttachedToWindow retries.
        tryBindSource()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollInvalidator)

        val source = boundSource
        if (source != null) {
            source.addConsumer(this)
        } else {
            tryBindSource()
        }

        getLocationInWindow(windowLocation)
        lastWindowX = windowLocation[0]
        lastWindowY = windowLocation[1]
        invalidate()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollInvalidator)
        boundSource?.removeConsumer(this)
        releaseRenderer()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        // The capture bakes in the source offset, so a layout/position change must re-record it.
        // RecyclerView-style scrolling moves items without a scroll notification; this catches it.
        getLocationInWindow(windowLocation)
        if (changed || windowLocation[0] != lastWindowX || windowLocation[1] != lastWindowY) {
            lastWindowX = windowLocation[0]
            lastWindowY = windowLocation[1]
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!enabled) return

        val source = boundSource
        val activeRenderer = obtainRenderer()
        if (source == null ||
            activeRenderer == null ||
            !canvas.isHardwareAccelerated ||
            Build.VERSION.SDK_INT < AGSL_MIN_API
        ) {
            drawFrostPanel(canvas)
            return
        }

        if (!drawGlass(canvas, source, activeRenderer)) {
            drawFrostPanel(canvas)
        }
    }

    private fun obtainRenderer(): ViewGlassRenderer? =
        if (Build.VERSION.SDK_INT >= AGSL_MIN_API) {
            renderer ?: ViewGlassRenderer().also { renderer = it }
        } else {
            null
        }

    @SuppressLint("NewApi")
    private fun drawGlass(
        canvas: Canvas,
        source: LumenGlassSourceLayout,
        activeRenderer: ViewGlassRenderer,
    ): Boolean {
        if (width <= 0 || height <= 0) return false

        val sourceNode = source.glassSourceNode() ?: return false
        if (!sourceNode.hasContent) return false

        val offset = offsetBetween(glass = this, source = source) ?: run {
            if (LumenGlassDebug.enabled) {
                Log.w(
                    TAG,
                    "LumenGlassView: the source and the glass do not share a window; drawing the " +
                        "frost fallback. (source=${source.id}, glass=${id})",
                )
            }
            return false
        }

        val features = resolvedFeatures
        if (!features.useShader) return false

        val density = resources.displayMetrics.density
        val plan = resolveGlassRenderPlan(
            spec = spec,
            widthPx = width.toFloat(),
            heightPx = height.toFloat(),
            density = density,
            features = features,
        )

        // Debug-only: report a source that does not cover the surface. Same decision function and
        // threshold as the Compose backend, in source-local coordinates.
        logOverlapVerdict(
            checkOverlap(
                surface = OverlapRect(
                    left = offset.x,
                    top = offset.y,
                    right = offset.x + width,
                    bottom = offset.y + height,
                ),
                capture = OverlapRect(
                    left = 0f,
                    top = 0f,
                    right = source.width.toFloat(),
                    bottom = source.height.toFloat(),
                ),
            ),
        )

        return activeRenderer.draw(
            canvas = canvas,
            surfaceWidthPx = width,
            surfaceHeightPx = height,
            offset = offset,
            plan = plan,
            features = features,
            source = sourceNode,
        )
    }

    private fun drawFrostPanel(canvas: Canvas) {
        val radiusPx = spec.cornerRadiusDp * resources.displayMetrics.density
        frostPanel.draw(canvas, width, height, radiusPx)
    }

    private fun tryBindSource() {
        if (boundSource != null || sourceResourceId == NO_ID) return

        when (val result = resolveSourceBinding(glass = this, sourceId = sourceResourceId)) {
            is SourceBindingResult.Bound -> bindSource(result.source)
            is SourceBindingResult.Rejected -> if (LumenGlassDebug.enabled) {
                Log.w(TAG, "LumenGlassView: ${result.detail}")
            }
        }
    }

    private fun bindSource(source: LumenGlassSourceLayout) {
        if (boundSource === source) return
        unbindSource()
        boundSource = source
        if (isAttachedToWindow) source.addConsumer(this)
        invalidate()
    }

    private fun unbindSource() {
        boundSource?.removeConsumer(this)
        boundSource = null
    }

    private fun releaseRenderer() {
        renderer?.release()
        renderer = null
    }
}
