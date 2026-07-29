package com.jiku.shared

import java.util.UUID

/**
 * One state change of a deposit-reservation booking (JIKU-55), published by the
 * booking module and consumed by the notification module — the same
 * publisher/consumer split as [ManualPaymentNotice]. PAYMENT_DECLARED and
 * DUPLICATE_REFERENCE alert the admin/sales mailbox; the rest notify the
 * customer. The account-access email for a newly provisioned tenant is *not*
 * sent from here — it goes out through the existing password-reset flow
 * ([TenantModuleApi.provisionTenant]), so DEPOSIT_VERIFIED here is only the
 * booking receipt, never a duplicate of that email.
 */
data class BookingNotice(
    val kind: String,
    val bookingId: UUID,
    val customerName: String,
    val customerEmail: String,
    val customerPhone: String,
    val reference: String? = null,
    val amountMinor: Long? = null,
    val currency: String = "GNF",
    /** DEPOSIT or BALANCE — only set for PAYMENT_DECLARED. */
    val declarationKind: String? = null,
    /** Only set for DEPOSIT_VERIFIED. */
    val balanceAmountMinor: Long? = null,
    val balanceDueDate: String? = null,
    /** Rejection reason; null for the other kinds. */
    val note: String? = null,
) {
    companion object {
        const val KIND_PAYMENT_DECLARED = "PAYMENT_DECLARED"
        const val KIND_DUPLICATE_REFERENCE = "DUPLICATE_REFERENCE"
        const val KIND_DEPOSIT_VERIFIED = "DEPOSIT_VERIFIED"
        const val KIND_BALANCE_VERIFIED = "BALANCE_VERIFIED"
        const val KIND_PAYMENT_REJECTED = "PAYMENT_REJECTED"
    }
}
