package com.jiku.shared

import java.util.UUID

/**
 * The paywall gate (JIKU-34), exposed in `shared` so the invitation module can
 * enforce the allowance without depending on the billing module — billing already
 * depends on invitation for metering, and a reverse dependency would form a cycle.
 * Billing provides the implementation; invitation consumes this interface.
 */
interface UsageAllowanceGate {
    /**
     * The currently unlocked ceiling for an event: the maximum number of distinct
     * guests that may be invited, directly comparable against the event's own
     * committed count ([alreadyCommitted], its non-failed invitations). Raised
     * by a paid unlock; while still on the free tier, this is computed from the
     * tenant's cumulative budget (JIKU-54), not a per-event default — the caller
     * must pass its own already-committed count so that contribution is not
     * subtracted twice.
     */
    fun allowanceCeiling(
        eventId: UUID,
        alreadyCommitted: Long,
    ): Long

    /**
     * Records that [newGuestCount] additional distinct guests were just
     * committed to [eventId]'s send (JIKU-54) — locks the free-tier portion in
     * against the tenant's cumulative budget. A no-op for an event already
     * unlocked to a paid tier. Call only after a batch enforced by
     * [allowanceCeiling] has actually been persisted.
     */
    fun recordCommitment(
        eventId: UUID,
        newGuestCount: Long,
    )
}
