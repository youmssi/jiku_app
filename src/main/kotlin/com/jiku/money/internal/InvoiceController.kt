package com.jiku.money.internal

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Organizer-facing invoicing (JIKU-69). Everything is tenant-scoped by the
 * persistence-layer filter, so an organizer only ever sees their own documents.
 *
 * Issuing is a POST because it has a permanent side effect — it consumes a number
 * in a gapless sequence — and is idempotent per payment so a retried request
 * cannot produce two invoices for one settlement.
 */
@RestController
@RequestMapping("/billing/invoices")
@PreAuthorize("hasRole('ORGANIZER')")
class InvoiceController(
    private val service: InvoiceService,
    private val renderer: InvoiceDocumentRenderer,
) {
    @GetMapping
    fun list(): List<InvoiceSummary> = service.list().map { it.toSummary() }

    @GetMapping("/{invoiceId}")
    fun get(
        @PathVariable invoiceId: UUID,
    ): InvoiceDetail = service.find(invoiceId).toDetail()

    @PostMapping("/payments/{paymentId}")
    fun issueForPayment(
        @PathVariable paymentId: UUID,
    ): InvoiceDetail = service.issueForPayment(paymentId).toDetail()

    @PostMapping("/{invoiceId}/credit-note")
    fun creditNote(
        @PathVariable invoiceId: UUID,
    ): InvoiceDetail = service.issueCreditNote(invoiceId).toDetail()

    @GetMapping("/{invoiceId}/document", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun document(
        @PathVariable invoiceId: UUID,
    ): ResponseEntity<ByteArray> {
        val invoice = service.find(invoiceId)
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${renderer.fileName(invoice)}\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(renderer.render(invoice))
    }
}

data class InvoiceSummary(
    val id: UUID,
    val invoiceNumber: String,
    val documentType: String,
    val currency: String,
    val totalMinor: Long,
    val issueDate: LocalDate,
    val issuedAt: Instant,
)

data class InvoiceDetail(
    val id: UUID,
    val invoiceNumber: String,
    val documentType: String,
    val correctedInvoiceId: UUID?,
    val buyer: InvoiceParty,
    val seller: InvoiceParty,
    val currency: String,
    val lines: List<InvoiceLineView>,
    val subtotalMinor: Long,
    val taxLabel: String?,
    val taxRate: BigDecimal,
    val taxAmountMinor: Long,
    val totalMinor: Long,
    val issueDate: LocalDate,
    val issuedAt: Instant,
    val paymentId: UUID?,
)

data class InvoiceParty(
    val legalName: String,
    val addressLine: String?,
    val city: String?,
    val country: String?,
    val registrationNumber: String?,
    val taxIdentifier: String?,
)

data class InvoiceLineView(
    val lineNumber: Int,
    val description: String,
    val quantity: Long,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long,
)

private fun Invoice.toSummary() =
    InvoiceSummary(
        id = requireNotNull(id),
        invoiceNumber = invoiceNumber,
        documentType = documentType.name,
        currency = currency,
        totalMinor = totalMinor,
        issueDate = issueDate,
        issuedAt = issuedAt,
    )

private fun Invoice.toDetail() =
    InvoiceDetail(
        id = requireNotNull(id),
        invoiceNumber = invoiceNumber,
        documentType = documentType.name,
        correctedInvoiceId = correctedInvoiceId,
        buyer =
            InvoiceParty(
                legalName = buyerLegalName,
                addressLine = buyerAddressLine,
                city = buyerCity,
                country = buyerCountry,
                registrationNumber = buyerRegistrationNumber,
                taxIdentifier = buyerTaxIdentifier,
            ),
        seller =
            InvoiceParty(
                legalName = sellerName,
                addressLine = sellerAddressLine,
                city = null,
                country = null,
                registrationNumber = null,
                taxIdentifier = sellerTaxIdentifier,
            ),
        currency = currency,
        lines =
            lines.map {
                InvoiceLineView(
                    lineNumber = it.lineNumber,
                    description = it.description,
                    quantity = it.quantity,
                    unitPriceMinor = it.unitPriceMinor,
                    lineTotalMinor = it.lineTotalMinor,
                )
            },
        subtotalMinor = subtotalMinor,
        taxLabel = taxLabel,
        taxRate = taxRate,
        taxAmountMinor = taxAmountMinor,
        totalMinor = totalMinor,
        issueDate = issueDate,
        issuedAt = issuedAt,
        paymentId = paymentId,
    )
