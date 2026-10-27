package com.jiku.messaging.internal

import com.jiku.shared.InvoiceIssuedNotice
import com.jiku.shared.async.Executors
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Emails an invoice issued when a payment was confirmed (JIKU-277) to the
 * organization's contact address, in its language, with the PDF attached.
 */
@Component
class InvoiceNoticeListener(
    private val mailer: OperationalMailer,
    private val templateRenderer: EmailTemplateRenderer,
    private val catalog: MessageCatalog,
) {
    @Async(Executors.BULK)
    @TransactionalEventListener(fallbackExecution = true)
    fun onInvoiceIssued(notice: InvoiceIssuedNotice) {
        mailer.send(
            "invoice",
            notice.recipientEmail,
            notice.recipientName,
            templateRenderer.renderInvoiceIssued(notice, catalog.language(notice.tenantId)),
            listOf(EmailAttachment(notice.fileName, PDF, notice.pdf)),
        )
    }

    private companion object {
        const val PDF = "application/pdf"
    }
}
