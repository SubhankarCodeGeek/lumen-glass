/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.android

/**
 * The colour of the FROST fallback panel — the appearance every backend draws when no shader can
 * run.
 *
 * ## Why one constant rather than one per backend
 *
 * The Compose backend (`LumenGlassNode.drawFrostPanel`) and the View backend
 * (`GlassFrostPanel`) must produce the *same* degraded appearance, because a consumer switching
 * from one API to the other should not see the fallback change. Defining the colour once makes
 * that agreement structural instead of a comment.
 *
 * `0x47` is alpha 71/255 ≈ 0.28. Deliberately visible but not opaque: the panel must read as
 * *frosted glass* — you should be able to tell something is behind it. An opaque panel would look
 * like a bug.
 */
internal const val FROST_PANEL_ARGB: Int = 0x47FFFFFF
