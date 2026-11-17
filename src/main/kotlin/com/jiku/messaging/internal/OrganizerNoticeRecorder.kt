package com.jiku.messaging.internal

import com.jiku.shared.InvoiceIssuedNotice
import com.jiku.shared.ManualPaymentNotice
import com.jiku.shared.PaymentRequestNotice
import com.jiku.shared.SubscriptionNotice
import com.jiku.shared.TenantContext
import com.jiku.shared.TrialNotice
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * Puts what billing announces in the organization's bell (JIKU-314), next to
 * the email the other listeners send. The organizer's own requests are left
 * out: they just made them, a notice would only repeat the screen.
 */
@Component
class OrganizerNoticeRecorder(
    private val notices: OrganizerNoticeService,
) {
    private val log = LoggerFactory.getLogger(OrganizerNoticeRecorder::class.java)

    @TransactionalEventListener(fallbackExecution = true)
    fun onTrial(notice: TrialNotice) =
        record(notice.tenantId, "trial.${notice.kind}", eventBilling(notice.eventId)) {
            mapOf(
                "guests" to notice.guests.toString(),
                "expiresAt" to notice.expiresAt.toString(),
                "note" to notice.note,
            )
        }

    @TransactionalEventListener(fallbackExecution = true)
    fun onManualPayment(notice: ManualPaymentNotice) {
        if (notice.kind == ManualPaymentNotice.KIND_REQUESTED) return
        record(notice.tenantId, "payment.${notice.kind}", eventBilling(notice.eventId)) {
            mapOf(
                "guests" to notice.guests.toString(),
                "amountMinor" to notice.amountMinor.toString(),
                "currency" to notice.currency,
                "reference" to notice.reference,
                "note" to notice.note,
            )
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onSubscription(notice: SubscriptionNotice) {
        if (notice.kind == SubscriptionNotice.KIND_REQUESTED) return
        record(notice.tenantId, "subscription.${notice.kind}", BILLING) {
            mapOf(
                "plan" to notice.plan,
                "months" to notice.months?.toString(),
                "expiresAt" to notice.expiresAt?.toString(),
                "suspensionAt" to notice.suspensionAt?.toString(),
            )
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onPaymentRequest(notice: PaymentRequestNotice) =
        record(notice.tenantId, "payment-request.${notice.kind}", BILLING) {
            mapOf(
                "reference" to notice.reference,
                "amountMinor" to notice.amountMinor.toString(),
                "currency" to notice.currency,
            )
        }

    @TransactionalEventListener(fallbackExecution = true)
    fun onInvoice(notice: InvoiceIssuedNotice) =
        record(notice.tenantId, "invoice.ISSUED", BILLING) {
            mapOf(
                "number" to notice.invoiceNumber,
                "amountMinor" to notice.totalMinor.toString(),
                "currency" to notice.currency,
            )
        }

    /** A failed notice must never undo or fail what was announced: the email still goes out. */
    private fun record(
        tenantId: String,
        kind: String,
        link: String?,
        params: () -> Map<String, String?>,
    ) {
        runCatching { TenantContext.withTenant(tenantId) { notices.record(kind, params(), link) } }
            .onFailure { log.error("Could not record the {} notice for the bell", kind, it) }
    }

    private fun eventBilling(eventId: UUID): String = "/events/$eventId/billing"

    private companion object {
        const val BILLING = "/billing"
    }
}
