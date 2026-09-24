package com.jiku.money.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantLegalIdentityInfo
import com.jiku.tenant.TenantModuleApi
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Issues invoices and credit notes (JIKU-69).
 *
 * Two properties carry the whole story and are why this is a service rather than
 * a mapper: numbers must be **gapless** within a tenant's fiscal year, and an
 * issued document must be **immutable**. The first is enforced by taking the
 * number inside the issuing transaction under a row lock, so a rollback returns
 * it. The second is enforced by the entity — every field is `val` — and by
 * corrections being new credit notes rather than edits.
 */
@Service
class InvoiceService(
    private val invoices: InvoiceRepository,
    private val counters: InvoiceNumberCounterRepository,
    private val allocator: InvoiceNumberAllocator,
    private val payments: PaymentRepository,
    private val tenants: TenantModuleApi,
    private val events: EventModuleApi,
    private val properties: InvoiceProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(InvoiceService::class.java)

    fun list(): List<Invoice> = invoices.findAllWithLinesOrderByIssuedAtDesc()

    fun find(id: UUID): Invoice =
        invoices.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice")
        }

    /**
     * Issues the invoice for a settled payment. Idempotent: a second call returns
     * the document already issued rather than burning another number, because a
     * retried request must not produce two invoices for one payment.
     */
    @Transactional
    fun issueForPayment(paymentId: UUID): Invoice {
        invoices.findByPaymentId(paymentId)?.let { return it }

        val payment =
            payments.findById(paymentId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "No such payment")
            }
        if (payment.status != PaymentStatus.SUCCEEDED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An invoice is issued only for a settled payment",
            )
        }

        val buyer = requireLegalIdentity()
        val eventName = payment.eventId?.let { events.findEvent(it)?.name }
        val description =
            listOfNotNull(
                "Jiku ${payment.tier} tier",
                eventName?.let { "— $it" },
            ).joinToString(" ")

        return issue(
            documentType = DocumentType.INVOICE,
            buyer = buyer,
            currency = payment.currency,
            lines = listOf(DraftLine(description, quantity = 1, unitPriceMinor = payment.amountMinor)),
            paymentId = paymentId,
            correctedInvoiceId = null,
        )
    }

    /**
     * Corrects an issued invoice with a credit note carrying the opposite sign.
     * The original is left untouched — that is the point of a credit note — and an
     * invoice can only be credited once.
     */
    @Transactional
    fun issueCreditNote(invoiceId: UUID): Invoice {
        val original = find(invoiceId)
        if (original.documentType != DocumentType.INVOICE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Only an invoice can be credited")
        }
        if (invoices.existsByCorrectedInvoiceId(invoiceId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This invoice has already been credited")
        }

        return issue(
            documentType = DocumentType.CREDIT_NOTE,
            buyer =
                TenantLegalIdentityInfo(
                    legalName = original.buyerLegalName,
                    registrationNumber = original.buyerRegistrationNumber,
                    taxIdentifier = original.buyerTaxIdentifier,
                    addressLine = original.buyerAddressLine,
                    city = original.buyerCity,
                    country = original.buyerCountry,
                ),
            currency = original.currency,
            lines =
                original.lines.map {
                    DraftLine(it.description, it.quantity, -it.unitPriceMinor)
                },
            paymentId = original.paymentId,
            correctedInvoiceId = invoiceId,
        )
    }

    private data class DraftLine(
        val description: String,
        val quantity: Long,
        val unitPriceMinor: Long,
    )

    private fun issue(
        documentType: DocumentType,
        buyer: TenantLegalIdentityInfo,
        currency: String,
        lines: List<DraftLine>,
        paymentId: UUID?,
        correctedInvoiceId: UUID?,
    ): Invoice {
        val issueDate = LocalDate.now(clock.withZone(ZoneId.of(properties.issueZone)))
        val fiscalYear = issueDate.year
        val sequence = nextSequenceNumber(fiscalYear)

        val treatment = properties.taxFor(buyer.country)
        val subtotal = lines.sumOf { it.quantity * it.unitPriceMinor }
        val taxAmount = taxOn(subtotal, treatment.rate)

        val prefix =
            if (documentType == DocumentType.CREDIT_NOTE) properties.creditNotePrefix else properties.numberPrefix
        val invoice =
            Invoice(
                invoiceNumber = "$prefix-$fiscalYear-%06d".format(sequence),
                fiscalYear = fiscalYear,
                sequenceNumber = sequence,
                documentType = documentType,
                buyerLegalName = buyer.legalName,
                buyerAddressLine = buyer.addressLine,
                buyerCity = buyer.city,
                buyerCountry = buyer.country.uppercase(),
                sellerName = properties.seller.name,
                currency = currency,
                subtotalMinor = subtotal,
                taxRate = treatment.rate,
                taxAmountMinor = taxAmount,
                totalMinor = subtotal + taxAmount,
                issueDate = issueDate,
            ).apply {
                this.correctedInvoiceId = correctedInvoiceId
                this.buyerRegistrationNumber = buyer.registrationNumber
                this.buyerTaxIdentifier = buyer.taxIdentifier
                this.sellerAddressLine = properties.seller.addressLine
                this.sellerTaxIdentifier = properties.seller.taxIdentifier
                this.taxLabel = treatment.label.takeIf { it.isNotBlank() }
                this.paymentId = paymentId
                this.lines =
                    lines
                        .mapIndexed { index, line ->
                            InvoiceLine(
                                lineNumber = index + 1,
                                description = line.description,
                                quantity = line.quantity,
                                unitPriceMinor = line.unitPriceMinor,
                                lineTotalMinor = line.quantity * line.unitPriceMinor,
                            )
                        }.toMutableList()
            }

        return invoices.save(invoice)
    }

    /**
     * Takes the next number for the year under a write lock held to commit, so two
     * concurrent issuers cannot read the same one and a rollback leaves the
     * counter untouched.
     *
     * The first invoice of a year races on inserting the counter itself; the
     * loser's unique constraint fires and it retries against the now-existing row.
     */
    private fun nextSequenceNumber(fiscalYear: Int): Long {
        // Creating the row runs in its own transaction, because a losing race
        // aborts the transaction it runs in and this one has an invoice to write.
        // The violation is caught here rather than inside the allocator: catching
        // it in there would leave that transaction rollback-only and fail its
        // commit. Either outcome is fine — the row exists once this returns.
        try {
            allocator.ensureCounterExists(fiscalYear)
        } catch (ex: DataIntegrityViolationException) {
            log.debug("Invoice counter for {} was created concurrently", fiscalYear, ex)
        }
        val counter =
            counters.findByFiscalYear(fiscalYear)
                ?: throw IllegalStateException("Invoice counter for $fiscalYear was not created")
        val taken = counter.nextNumber
        counter.nextNumber = taken + 1
        counters.save(counter)
        return taken
    }

    private fun taxOn(
        subtotalMinor: Long,
        rate: BigDecimal,
    ): Long =
        BigDecimal(subtotalMinor)
            .multiply(rate)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()

    private fun requireLegalIdentity(): TenantLegalIdentityInfo {
        val tenantId =
            TenantContext.get()
                ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "No organization bound to this request")
        val tenant = tenants.findTenant(UUID.fromString(tenantId))
        return tenant?.legalIdentity
            ?: throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Add your organization's legal details in Settings before issuing an invoice",
            )
    }
}
