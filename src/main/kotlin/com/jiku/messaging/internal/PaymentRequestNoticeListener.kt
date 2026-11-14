package com.jiku.messaging.internal

import com.jiku.shared.MoneyFormat
import com.jiku.shared.PaymentRequestNotice
import com.jiku.shared.async.Executors
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Tells the organizer that a transfer request closed without payment (JIKU-307),
 * in the organization's language, so they can make a new one if they still need
 * it. Content only: the delivery mechanics live in [OperationalMailer].
 */
@Component
class PaymentRequestNoticeListener(
    private val mailer: OperationalMailer,
    private val catalog: MessageCatalog,
) {
    private val log = LoggerFactory.getLogger(PaymentRequestNoticeListener::class.java)

    @Async(Executors.URGENT)
    @TransactionalEventListener(fallbackExecution = true)
    fun onPaymentRequestNotice(notice: PaymentRequestNotice) {
        val language = catalog.language(notice.tenantId)
        val values =
            mapOf(
                "reference" to notice.reference,
                "purchase" to notice.purchase,
                "amount" to MoneyFormat.format(notice.amountMinor, notice.currency),
            )
        val copy = catalog.noticeCopy(language, "payment-request", notice.kind, values)
        if (copy == null) {
            log.warn("Ignoring payment request notice of unknown kind: {}", notice.kind)
            return
        }
        mailer.sendNotice("payment-request", notice.organizerEmail, notice.organizerName, language, copy)
    }
}
