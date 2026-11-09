/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import android.view.View
import android.view.ViewParent

/**
 * Why a `lumenGlassSource` reference could not be bound to a glass surface.
 *
 * The rejection is a value rather than a silent fallback: an unusable source must be diagnosable at
 * the call site, not discovered later as a near-black render (the failure class described in
 * ARCHITECTURE §4).
 */
internal enum class SourceBindingReason {
    /** No view exists for the reference id in the glass's view tree. */
    MISSING,

    /**
     * The referenced view exists but is not a [LumenGlassSourceLayout].
     *
     * This is the only way an arbitrary view reaches the binder: the consumer-facing `setSource`
     * will be typed as [LumenGlassSourceLayout], so passing any other `View` there is a compile
     * error. An XML `lumenGlassSource` reference, however, is only classified at resolve time and
     * therefore resolves to this reason for every other type.
     */
    WRONG_TYPE,

    /** The glass surface referenced itself. */
    SELF_REFERENCE,

    /** The referenced view is an ancestor of the glass surface. */
    ANCESTOR_REFERENCE,
}

/**
 * The facts [decideSourceBinding] needs, with no Android types.
 *
 * Keeping the decision input this small is what makes the whole matrix JVM-unit-testable without a
 * device, Robolectric, or a live view hierarchy.
 *
 * @property exists `false` when `findViewById` returned no view.
 * @property isSourceLayout `true` when the resolved view is a [LumenGlassSourceLayout].
 * @property isSelf `true` when the resolved view is the glass surface itself.
 * @property isAncestor `true` when the resolved view is an ancestor of the glass surface.
 */
internal data class SourceCandidate(
    val exists: Boolean,
    val isSourceLayout: Boolean,
    val isSelf: Boolean,
    val isAncestor: Boolean,
)

/**
 * The verdict of the source-binding decision.
 *
 * [Rejected.detail] is human-readable and intended for the debug log only. This layer never logs or
 * draws: the future `LumenGlassView` owns the debug log and the frost fallback.
 */
internal sealed interface SourceBindingDecision {
    /** The candidate is a usable source. */
    data object Bound : SourceBindingDecision

    /** The candidate cannot be sampled; [reason] says why. */
    data class Rejected(val reason: SourceBindingReason, val detail: String) :
        SourceBindingDecision
}

/**
 * Decides whether a source candidate may be bound, with a deliberate precedence order.
 *
 * Precedence, highest first:
 *
 * 1. [SourceBindingReason.MISSING] — there is nothing to inspect.
 * 2. [SourceBindingReason.SELF_REFERENCE] — a surface sampling itself is nonsensical even if the
 *    candidate would otherwise be a valid type.
 * 3. [SourceBindingReason.ANCESTOR_REFERENCE] — an ancestor contains the glass, so sampling it
 *    would recurse through the glass itself.
 * 4. [SourceBindingReason.WRONG_TYPE] — the candidate exists but is the wrong class.
 * 5. [SourceBindingDecision.Bound] — a [LumenGlassSourceLayout] that is neither self nor ancestor.
 *
 * Pure: no Android types, no logging, no drawing. Unit-tested by `SourceBindingTest`.
 *
 * @param candidate the facts gathered about the referenced view.
 * @return [SourceBindingDecision.Bound] or a [SourceBindingDecision.Rejected] carrying the reason
 *   and an actionable detail.
 */
internal fun decideSourceBinding(candidate: SourceCandidate): SourceBindingDecision {
    return when {
        !candidate.exists -> SourceBindingDecision.Rejected(
            reason = SourceBindingReason.MISSING,
            detail = "lumenGlassSource matched no view in the glass's view tree.",
        )
        candidate.isSelf -> SourceBindingDecision.Rejected(
            reason = SourceBindingReason.SELF_REFERENCE,
            detail = "lumenGlassSource resolves to the glass surface itself; a surface cannot " +
                "sample itself.",
        )
        candidate.isAncestor -> SourceBindingDecision.Rejected(
            reason = SourceBindingReason.ANCESTOR_REFERENCE,
            detail = "lumenGlassSource resolves to an ancestor of the glass surface; sampling a " +
                "parent would recurse through the glass itself.",
        )
        !candidate.isSourceLayout -> SourceBindingDecision.Rejected(
            reason = SourceBindingReason.WRONG_TYPE,
            detail = "lumenGlassSource must resolve to a LumenGlassSourceLayout; an arbitrary " +
                "view cannot be captured correctly (parent transform, alpha and clip are lost).",
        )
        else -> SourceBindingDecision.Bound
    }
}

/**
 * The outcome of resolving a `lumenGlassSource` reference against a real view tree.
 *
 * Exactly one state applies: [Bound] carries the source to capture; [Rejected] carries the reason
 * and a human-readable detail for the debug log.
 */
internal sealed interface SourceBindingResult {
    /** The reference resolved to a usable source. */
    data class Bound(val source: LumenGlassSourceLayout) : SourceBindingResult

    /** The reference could not be used; [reason] says why. */
    data class Rejected(val reason: SourceBindingReason, val detail: String) : SourceBindingResult
}

/**
 * Resolves [sourceId] against [glass]'s root view and classifies the result.
 *
 * This is pure classification: it neither logs nor draws, so the future `LumenGlassView` remains the
 * single owner of the debug log and the frost fallback. The bound source is returned rather than
 * stored, keeping the function side-effect free.
 *
 * The consumer-facing `setSource` will be typed as [LumenGlassSourceLayout], making the
 * arbitrary-View case a compile error. XML cannot enforce that, so a `lumenGlassSource` reference to
 * any other view type is classified as [SourceBindingReason.WRONG_TYPE] here.
 *
 * @param glass the glass surface resolving the reference; used for `rootView` and identity checks.
 * @param sourceId the resource id from `lumenGlassSource`.
 * @return the bound [LumenGlassSourceLayout], or a rejection with a human-readable detail.
 */
internal fun resolveSourceBinding(glass: View, sourceId: Int): SourceBindingResult {
    val source: View? = glass.rootView.findViewById(sourceId)
    val candidate = SourceCandidate(
        exists = source != null,
        isSourceLayout = source is LumenGlassSourceLayout,
        isSelf = source != null && source === glass,
        isAncestor = source != null && isAncestorOf(candidate = source, descendant = glass),
    )

    return when (val decision = decideSourceBinding(candidate)) {
        // `Bound` implies both `exists` and `isSourceLayout`, so this cast cannot fail.
        SourceBindingDecision.Bound -> SourceBindingResult.Bound(
            source = source as LumenGlassSourceLayout,
        )
        is SourceBindingDecision.Rejected -> SourceBindingResult.Rejected(
            reason = decision.reason,
            detail = "${decision.detail} " +
                "(lumenGlassSource=@0x${Integer.toHexString(sourceId)}, " +
                "resolved=${source?.javaClass?.name ?: "none"})",
        )
    }
}

/**
 * `true` when [candidate] is an ancestor of [descendant].
 *
 * Walks the parent chain with identity comparison. A `ViewParent` may not be a `View` (for example
 * a `ViewRootImpl`), so comparing `ViewParent` values directly is both sufficient and correct.
 */
private fun isAncestorOf(candidate: View, descendant: View): Boolean {
    var parent: ViewParent? = descendant.parent
    while (parent != null) {
        if (parent === candidate) return true
        parent = parent.parent
    }
    return false
}
