package com.jiku.shared

import java.time.Instant
import java.util.UUID

/**
 * One step of a gift's life (JIKU-315), published by billing and consumed by
 * notification, like [TrialNotice]: offered (or applied at once by the desk),
 * about to expire unclaimed, expired unclaimed, or revoked. Activation by the
 * organizer is their own act and is not announced back to them.
 */
data class GiftNotice(
    val kind: String,
    val giftId: UUID,
    val tenantId: String,
    /** EVENT_GUESTS, SERVICES_PLAN or ORGANIZER_PACK. */
    val giftKind: String,
    val guests: Long?,
    val days: Int?,
    val plan: String?,
    val months: Int?,
    val claimBy: Instant,
    val message: String?,
    val organizerName: String,
    val organizerEmail: String,
) {
    companion object {
        const val KIND_OFFERED = "OFFERED"
        const val KIND_APPLIED = "APPLIED"
        const val KIND_EXPIRING = "EXPIRING"
        const val KIND_EXPIRED = "EXPIRED"
        const val KIND_REVOKED = "REVOKED"
    }
}
