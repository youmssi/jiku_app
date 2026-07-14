package com.jiku.shared

import java.util.UUID

/**
 * One state change of a manual (concierge) payment (JIKU-41), published by the
 * billing module and consumed by the notification module — the same
 * publisher/consumer split as [EventCancellationNotice], so neither module
 * depends on the other. REQUESTED notifies the sales team of a new activation
 * request; CONFIRMED and REJECTED notify the organizer of the outcome.
 */
data class ManualPaymentNotice(
    val kind: String,
    val paymentId: UUID,
    val tenantId: String,
    val eventId: UUID,
    val tier: String,
    val amountMinor: Long,
    val currency: String,
    /** The transfer reference the client must include with their payment. */
    val reference: String,
    val organizerName: String,
    val organizerEmail: String,
    /** Rejection reason; null for the other kinds. */
    val note: String? = null,
) {
    companion object {
        const val KIND_REQUESTED = "REQUESTED"
        const val KIND_CONFIRMED = "CONFIRMED"
        const val KIND_REJECTED = "REJECTED"
    }
}
