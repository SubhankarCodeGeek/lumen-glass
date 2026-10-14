/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.subhankar.lumenglass.core.Bezel
import com.subhankar.lumenglass.core.GlassSpec
import com.subhankar.lumenglass.core.LensModel

/**
 * The receiver for the `Modifier.lumenGlass { ... }` configuration lambda.
 *
 * ## Why this type exists
 *
 * `ARCHITECTURE.md` §6 sketches the API as a trailing lambda assigning to properties:
 *
 * ```kotlin
 * Modifier.lumenGlass(state) { refraction = 1.0f; frost = 6.dp }
 * ```
 *
 * That needs a mutable receiver. [GlassSpec] is deliberately an **immutable `data class`** — it is
 * the platform-agnostic description of the effect, it is `equals`/`copy`-able, and the shader maths
 * is a pure function of it. Making it mutable to serve the DSL would be the wrong trade.
 *
 * So the DSL builds into this mutable receiver and produces an immutable [GlassSpec] at the end.
 * Consumers get the documented ergonomics; `core/` keeps its guarantees.
 *
 * ## Units
 *
 * [frost] and [BezelBuilder.radius] are [Dp] (a Compose type, which `core/` may not use), so they
 * are converted to the raw values [GlassSpec] stores **at this boundary** — the one place that
 * already knows about density. Everything else is already unitless.
 */
public class GlassSpecBuilder {
    /** Which lens model to use — centre, rim, or a blend. The library's differentiator. */
    public var lens: LensModel = LensModel.Blend(centre = 0.6f, rim = 0.4f)

    /** Overall displacement strength. `0` = no bending (the glass becomes a plain frost). */
    public var refraction: Float = 1.0f

    /** Chromatic aberration. `0` disables it (and cheaper tiers ignore it regardless). */
    public var dispersion: Float = 0f

    /** Frost (blur) radius. `0.dp` = no blur. */
    public var frost: Dp = 6.dp

    /**
     * Material tint. Expects its **own alpha** — a fully transparent colour adds no tint, which is
     * usually not what you want. Use e.g. `Color.White.copy(alpha = 0.15f)`.
     */
    public var tint: Color = Color.Unspecified

    /** Colour saturation multiplier. `1f` = unchanged. */
    public var saturation: Float = 1f

    /** Colour contrast multiplier. `1f` = unchanged. */
    public var contrast: Float = 1f

    /** Strength of the specular edge band. `0f` disables it. */
    public var edgeLight: Float = 0.1f

    /**
     * Corner radius of the glass surface.
     *
     * Informs the shader's signed distance field (SDF) of the surface curvature, ensuring
     * refraction, specular chamfer highlights, and the inner bezel shadow perfectly follow
     * rounded card corners. Default is `0.dp` (sharp rectangle).
     */
    public var cornerRadius: Dp = 0.dp

    /** Optional inner-shadow bezel. Configure via [bezel]. */
    public var bezel: BezelBuilder? = null

    /**
     * Configures the optional bezel inner-shadow.
     *
     * ```kotlin
     * Modifier.lumenGlass(state) {
     *     bezel { radius = 12.dp; alpha = 0.12f }
     * }
     * ```
     */
    public fun bezel(block: BezelBuilder.() -> Unit) {
        bezel = BezelBuilder().apply(block)
    }

    /**
     * When true (default), enables Apple-calibrated dual-lobe hairline highlights, asymmetric
     * directional lighting, and normal-aligned rim refraction. When false, runs legacy diffuse mode.
     */
    public var precisionMode: Boolean = true

    /**
     * Edge bending pattern (Pattern A Toric by default).
     */
    public var rimProfile: com.subhankar.lumenglass.core.RimProfile = com.subhankar.lumenglass.core.RimProfile.TORIC

    /**
     * Builds the immutable [GlassSpec].
     *
     * `internal` — consumers describe what they want and the modifier constructs the spec; they never
     * need to see this step.
     *
     * @param density the current display density, supplied by the caller because the builder is not
     *   `@Composable` and so cannot read `LocalDensity` itself. Used to convert the dp-valued fields.
     */
    internal fun build(density: Float): GlassSpec = GlassSpec(
        lens = lens,
        refraction = refraction,
        dispersion = dispersion,
        frostDp = frost.value * density,
        // `Color.Unspecified` means "no tint". Its `toArgb()` is opaque black, which would be a very
        // surprising default, so it is mapped to 0 (fully transparent) explicitly.
        tintArgb = if (tint == Color.Unspecified) 0 else tint.toArgb(),
        saturation = saturation,
        contrast = contrast,
        edgeLight = edgeLight,
        bezel = bezel?.let { b ->
            Bezel(
                radiusDp = b.radius.value * density,
                offsetYDp = b.offsetY.value * density,
                alpha = b.alpha,
            )
        },
        cornerRadiusDp = cornerRadius.value * density,
        precisionMode = precisionMode,
        rimProfile = rimProfile,
    )
}

/** Builder for [Bezel]. See [GlassSpecBuilder.bezel]. */
public class BezelBuilder {
    /** Width of the shadow band, inward from the edge. */
    public var radius: Dp = 12.dp

    /** Vertical offset of the shadow. */
    public var offsetY: Dp = 4.dp

    /** Shadow strength, `0f`..`1f`. */
    public var alpha: Float = 0.12f
}
