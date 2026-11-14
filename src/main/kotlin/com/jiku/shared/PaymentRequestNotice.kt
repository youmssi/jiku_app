package com.jiku.shared

/**
 * A manual payment request that closed without being paid (JIKU-307), published
 * by the money module and emailed to the organizer by messaging. Content only:
 * never a payment credential.
 */
data class PaymentRequestNotice(
    val kind: String,
    val tenantId: String,
    /** The transfer reference the organizer was given. */
    val reference: String,
    /** What the request was for, in words. */
    val purchase: String,
    val amountMinor: Long,
    val currency: String,
    val organizerName: String,
    val organizerEmail: String,
) {
    companion object {
        /** No transfer was confirmed within the expiry delay. */
        const val KIND_EXPIRED = "EXPIRED"
    }
}
