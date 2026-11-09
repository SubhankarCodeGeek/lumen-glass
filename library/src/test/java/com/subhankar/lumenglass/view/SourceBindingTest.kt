/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the source-binding decision matrix.
 *
 * The contract is that a glass surface may only sample a [LumenGlassSourceLayout], and that every
 * other reference is rejected with an explicit, diagnosable reason rather than a silent near-black
 * render. [decideSourceBinding] is pure precisely so the whole matrix can be pinned here without a
 * device.
 */
class SourceBindingTest {

    @Test
    fun missingReference_isRejectedAsMissing() {
        val reason = reasonFor(candidate(exists = false, isSourceLayout = false))

        assertEquals(SourceBindingReason.MISSING, reason)
    }

    @Test
    fun arbitraryView_isRejectedAsWrongType() {
        val reason = reasonFor(candidate(exists = true, isSourceLayout = false))

        assertEquals(SourceBindingReason.WRONG_TYPE, reason)
    }

    @Test
    fun selfReference_isRejectedAsSelfReference() {
        // The candidate is otherwise a valid source type: self-reference must still win.
        val reason = reasonFor(candidate(exists = true, isSourceLayout = true, isSelf = true))

        assertEquals(SourceBindingReason.SELF_REFERENCE, reason)
    }

    @Test
    fun ancestorReference_isRejectedAsAncestorReference() {
        // The candidate is otherwise a valid source type: being an ancestor must still win.
        val reason = reasonFor(candidate(exists = true, isSourceLayout = true, isAncestor = true))

        assertEquals(SourceBindingReason.ANCESTOR_REFERENCE, reason)
    }

    @Test
    fun validSourceLayout_isBound() {
        val decision = decideSourceBinding(candidate(exists = true, isSourceLayout = true))

        assertEquals(SourceBindingDecision.Bound, decision)
    }

    @Test
    fun selfPlusWrongType_prefersSelfReference() {
        val reason = reasonFor(candidate(exists = true, isSourceLayout = false, isSelf = true))

        assertEquals(SourceBindingReason.SELF_REFERENCE, reason)
    }

    @Test
    fun ancestorPlusWrongType_prefersAncestorReference() {
        val reason = reasonFor(candidate(exists = true, isSourceLayout = false, isAncestor = true))

        assertEquals(SourceBindingReason.ANCESTOR_REFERENCE, reason)
    }

    @Test
    fun missingBeatsEveryOtherFlag() {
        val reason = reasonFor(
            candidate(exists = false, isSourceLayout = false, isSelf = true, isAncestor = true),
        )

        assertEquals(SourceBindingReason.MISSING, reason)
    }

    @Test
    fun everyRejectionCarriesADetailForTheDebugLog() {
        val candidates = listOf(
            candidate(exists = false, isSourceLayout = false),
            candidate(exists = true, isSourceLayout = false),
            candidate(exists = true, isSourceLayout = false, isSelf = true),
            candidate(exists = true, isSourceLayout = false, isAncestor = true),
        )

        candidates.forEach { candidate ->
            val rejected = decideSourceBinding(candidate) as SourceBindingDecision.Rejected
            assertTrue(
                "rejection detail must be actionable for $candidate",
                rejected.detail.isNotBlank(),
            )
        }
    }

    private fun reasonFor(candidate: SourceCandidate): SourceBindingReason {
        val decision = decideSourceBinding(candidate)
        assertTrue("expected a rejection, was $decision", decision is SourceBindingDecision.Rejected)
        return (decision as SourceBindingDecision.Rejected).reason
    }

    private fun candidate(
        exists: Boolean,
        isSourceLayout: Boolean,
        isSelf: Boolean = false,
        isAncestor: Boolean = false,
    ): SourceCandidate = SourceCandidate(
        exists = exists,
        isSourceLayout = isSourceLayout,
        isSelf = isSelf,
        isAncestor = isAncestor,
    )
}
