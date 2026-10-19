package com.jiku.money.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.shared.MoneyFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Organizer-facing billing history (JIKU-35): past payments and a simple
 * downloadable receipt per successful one. Everything is tenant-scoped by the
 * persistence-layer filter, so an organizer only ever sees their own payments.
 *
 * Not accounting-grade (no sequential invoice numbers or tax breakdowns) — a
 * deliberate MVP scope cut, to revisit before any market with stricter invoicing.
 */
@RestController
@RequestMapping("/billing/payments")
@PreAuthorize("hasRole('ORGANIZER')")
class BillingHistoryController(
    private val payments: PaymentRepository,
    private val events: EventModuleApi,
    private val paymentService: PaymentService,
) {
    @GetMapping
    fun history(): List<PaymentHistoryItem> =
        payments.findByOrderByCreatedAtDesc().map { payment ->
            PaymentHistoryItem(
                paymentId = requireNotNull(payment.id),
                eventId = payment.eventId,
                eventName =
                    label(payment),
                tier = payment.tier,
                kind = payment.kind,
                guests = payment.guests,
                amountMinor = payment.amountMinor,
                currency = payment.currency,
                status = payment.status.name,
                createdAt = payment.createdAt,
            )
        }

    /** One payment's status, polled by the page the payer returns to from the provider (JIKU-164). */
    @GetMapping("/{paymentId}")
    fun status(
        @PathVariable paymentId: UUID,
    ): PaymentStatusView = paymentService.status(paymentId)

    @GetMapping("/{paymentId}/receipt", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun receipt(
        @PathVariable paymentId: UUID,
    ): String {
        val payment =
            payments.findById(paymentId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found")
            }
        if (payment.status != PaymentStatus.SUCCEEDED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A receipt is only available for a successful payment")
        }
        val purchase = label(payment)
        val when0 = RECEIPT_DATE.format(payment.createdAt)
        val amount = MoneyFormat.format(payment.amountMinor, payment.currency)
        return buildString {
            appendLine("Jikū — Payment receipt")
            appendLine("========================")
            appendLine("Receipt reference : ${payment.id}")
            appendLine("Date              : $when0")
            appendLine("Purchase          : $purchase")
            appendLine("Amount            : $amount")
            appendLine("Payment method    : Mobile Money (${payment.provider})")
            appendLine("Provider reference: ${payment.providerReference ?: "-"}")
            appendLine("Status            : ${payment.status.name}")
            appendLine()
            appendLine("Amount includes all taxes. This is a basic receipt for your records, not a tax invoice.")
        }
    }

    private fun label(payment: Payment): String = paymentLabel(payment, payment.eventId?.let { events.findEvent(it)?.name })

    private companion object {
        val RECEIPT_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}

data class PaymentHistoryItem(
    val paymentId: UUID,
    /** Nul pour un renouvellement d'abonnement (JIKU-90). */
    val eventId: UUID?,
    val eventName: String,
    val tier: String,
    /** GUESTS, SUBSCRIPTION, PACK, PACK_EXTRA, WHATSAPP_NUMBER or COMMISSION. */
    val kind: String,
    /** Guests the payment bought or settled; null when it bought none. */
    val guests: Long?,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val createdAt: Instant,
)
