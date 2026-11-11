package com.jiku.money.internal

import com.jiku.shared.InvoiceIssuedNotice
import com.jiku.shared.LegalIdentityCompleted
import com.jiku.shared.TenantContext
import com.jiku.shared.async.Executors
import com.jiku.tenant.TenantModuleApi
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * Issues an invoice as soon as a payment is confirmed (JIKU-277), online or by
 * the team, and has it emailed with the PDF. It runs after the grant commits and
 * off the request, so an invoice that cannot be issued or sent never holds back
 * what was paid for. Without a complete legal identity nothing is issued; the
 * invoices still owed are issued the moment the identity is completed.
 */
@Component
class AutomaticInvoicing(
    private val invoiceService: InvoiceService,
    private val renderer: InvoiceDocumentRenderer,
    private val tenants: TenantModuleApi,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(AutomaticInvoicing::class.java)

    @Async(Executors.BULK)
    @TransactionalEventListener
    fun onPaymentFulfilled(fulfilled: PaymentFulfilled) {
        TenantContext.withTenant(fulfilled.tenantId) { issueAndSend(fulfilled.paymentId) }
    }

    @Async(Executors.BULK)
    @TransactionalEventListener
    fun onLegalIdentityCompleted(completed: LegalIdentityCompleted) {
        TenantContext.withTenant(completed.tenantId) {
            invoiceService.uninvoicedPayments().forEach { issueAndSend(it) }
        }
    }

    private fun issueAndSend(paymentId: UUID) {
        try {
            val issued = invoiceService.issueAutomatically(paymentId) ?: return
            if (!issued.created) return
            val invoice = issued.invoice
            val tenant = tenants.findTenant(UUID.fromString(requireNotNull(TenantContext.get())))
            events.publishEvent(
                InvoiceIssuedNotice(
                    tenantId = requireNotNull(TenantContext.get()),
                    invoiceId = requireNotNull(invoice.id),
                    invoiceNumber = invoice.invoiceNumber,
                    totalMinor = invoice.totalMinor,
                    currency = invoice.currency,
                    recipientEmail = tenant?.contactEmail.orEmpty(),
                    recipientName = tenant?.displayName ?: invoice.buyerLegalName,
                    fileName = renderer.fileName(invoice),
                    pdf = renderer.render(invoice),
                ),
            )
        } catch (ex: Exception) {
            log.error("Could not issue or send the invoice of payment {}", paymentId, ex)
        }
    }
}
