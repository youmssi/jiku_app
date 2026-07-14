package com.jiku.billing.internal

import com.jiku.event.EventModuleApi
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
) {
    @GetMapping
    fun history(): List<PaymentHistoryItem> =
        payments.findByOrderByCreatedAtDesc().map { payment ->
            PaymentHistoryItem(
                paymentId = requireNotNull(payment.id),
                eventId = payment.eventId,
                eventName = events.findEvent(payment.eventId)?.name ?: "Event",
                tier = payment.tier,
                amountMinor = payment.amountMinor,
                currency = payment.currency,
                status = payment.status.name,
                createdAt = payment.createdAt,
            )
        }

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
        val eventName = events.findEvent(payment.eventId)?.name ?: "Event"
        val when0 = RECEIPT_DATE.format(payment.createdAt)
        val amount = formatAmount(payment.amountMinor, payment.currency)
        return buildString {
            appendLine("Jikū — Payment receipt")
            appendLine("========================")
            appendLine("Receipt reference : ${payment.id}")
            appendLine("Date              : $when0")
            appendLine("Event             : $eventName")
            appendLine("Tier unlocked     : ${payment.tier}")
            appendLine("Amount            : $amount")
            appendLine("Payment method    : Mobile Money (${payment.provider})")
            appendLine("Provider reference: ${payment.providerReference ?: "-"}")
            appendLine("Status            : ${payment.status.name}")
            appendLine()
            appendLine("This is a basic receipt for your records, not a tax invoice.")
        }
    }

    private fun formatAmount(
        minor: Long,
        currency: String,
    ): String {
        val major = minor / 100
        val cents = minor % 100
        return "%,d.%02d %s".format(major, cents, currency)
    }

    private companion object {
        val RECEIPT_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}

data class PaymentHistoryItem(
    val paymentId: UUID,
    val eventId: UUID,
    val eventName: String,
    val tier: String,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val createdAt: Instant,
)
