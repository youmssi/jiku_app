package com.jiku.shared

import java.util.UUID

/**
 * An invoice issued on its own when a payment was confirmed (JIKU-277), published
 * by the billing module and emailed by the notification module with the PDF
 * attached, so neither module depends on the other.
 */
class InvoiceIssuedNotice(
    val tenantId: String,
    val invoiceId: UUID,
    val invoiceNumber: String,
    val totalMinor: Long,
    val currency: String,
    val recipientEmail: String,
    val recipientName: String,
    val fileName: String,
    val pdf: ByteArray,
)

/**
 * An organization's legal identity became complete enough to address an invoice
 * (JIKU-277): the billing module issues the invoices it could not issue before.
 */
data class LegalIdentityCompleted(
    val tenantId: String,
)
