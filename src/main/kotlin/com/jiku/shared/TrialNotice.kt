package com.jiku.shared

import java.time.Instant
import java.util.UUID

/**
 * One state change of a trial allowance (JIKU-42), published by billing and
 * consumed by notification — the same split as [ManualPaymentNotice]. Every kind
 * emails the organizer: what they got, when it runs out, or how it ended.
 */
data class TrialNotice(
    val kind: String,
    val trialId: UUID,
    val tenantId: String,
    val eventId: UUID,
    val tier: String,
    val expiresAt: Instant,
    val organizerName: String,
    val organizerEmail: String,
    /** End-early reason; null for the other kinds. */
    val note: String? = null,
) {
    companion object {
        const val KIND_GRANTED = "GRANTED"
        const val KIND_EXPIRING = "EXPIRING"
        const val KIND_EXPIRED = "EXPIRED"
        const val KIND_ENDED = "ENDED"
    }
}
