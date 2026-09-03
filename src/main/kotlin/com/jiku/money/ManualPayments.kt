package com.jiku.money

import java.time.Instant
import java.util.UUID

/**
 * Payment instructions for a manual (concierge) activation request (JIKU-41):
 * what to pay, where to send it, and the reference to quote. Details flow one
 * way — platform to client; no client payment credential is ever collected.
 */
data class ManualPaymentInstructions(
    val paymentId: UUID,
    val reference: String,
    val tier: String,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val payee: PayeeDetails,
)

data class PayeeDetails(
    val payeeName: String?,
    val mobileMoneyNumber: String?,
    val mobileMoneyOperator: String?,
    val bankDetails: String?,
)

/** One payment row as the platform back-office sees it (cross-tenant). */
data class AdminPaymentView(
    val id: UUID,
    val tenantId: String,
    val eventId: UUID,
    val tier: String,
    val amountMinor: Long,
    val currency: String,
    val provider: String,
    val reference: String,
    val status: String,
    val createdAt: Instant,
)
