package com.jiku.billing.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * An issued invoice or credit note (JIKU-69). Tenant-scoped.
 *
 * **Immutable once issued.** Every field is `val`: an invoice that has reached a
 * buyer's accounts department cannot be edited, so a correction is a new
 * [DocumentType.CREDIT_NOTE] pointing back at the original through
 * [correctedInvoiceId]. That is also why both parties and the tax rate are
 * snapshotted here rather than read live — changing an address or a configured
 * rate must never rewrite a document already sent.
 */
@Entity
@Table(
    name = "invoice",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_invoice_number",
            columnNames = ["tenant_id", "fiscal_year", "sequence_number"],
        ),
    ],
)
class Invoice(
    @Column(name = "invoice_number", nullable = false, updatable = false)
    val invoiceNumber: String,
    @Column(name = "fiscal_year", nullable = false, updatable = false)
    val fiscalYear: Int,
    @Column(name = "sequence_number", nullable = false, updatable = false)
    val sequenceNumber: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, updatable = false)
    val documentType: DocumentType,
    @Column(name = "buyer_legal_name", nullable = false, updatable = false)
    val buyerLegalName: String,
    @Column(name = "buyer_address_line", nullable = false, updatable = false)
    val buyerAddressLine: String,
    @Column(name = "buyer_city", nullable = false, updatable = false)
    val buyerCity: String,
    @Column(name = "buyer_country", nullable = false, updatable = false)
    val buyerCountry: String,
    @Column(name = "seller_name", nullable = false, updatable = false)
    val sellerName: String,
    @Column(name = "currency", nullable = false, updatable = false)
    val currency: String,
    @Column(name = "subtotal_minor", nullable = false, updatable = false)
    val subtotalMinor: Long,
    @Column(name = "tax_rate", nullable = false, updatable = false)
    val taxRate: BigDecimal,
    @Column(name = "tax_amount_minor", nullable = false, updatable = false)
    val taxAmountMinor: Long,
    @Column(name = "total_minor", nullable = false, updatable = false)
    val totalMinor: Long,
    @Column(name = "issue_date", nullable = false, updatable = false)
    val issueDate: LocalDate,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "corrected_invoice_id", updatable = false)
    var correctedInvoiceId: UUID? = null

    @Column(name = "buyer_registration_number", updatable = false)
    var buyerRegistrationNumber: String? = null

    @Column(name = "buyer_tax_identifier", updatable = false)
    var buyerTaxIdentifier: String? = null

    @Column(name = "seller_address_line", updatable = false)
    var sellerAddressLine: String? = null

    @Column(name = "seller_tax_identifier", updatable = false)
    var sellerTaxIdentifier: String? = null

    @Column(name = "tax_label", updatable = false)
    var taxLabel: String? = null

    @Column(name = "payment_id", updatable = false)
    var paymentId: UUID? = null

    @Column(name = "issued_at", nullable = false, updatable = false)
    val issuedAt: Instant = Instant.now()

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "invoice_id", nullable = false)
    @OrderBy("lineNumber ASC")
    var lines: MutableList<InvoiceLine> = mutableListOf()
}

enum class DocumentType {
    INVOICE,

    /** Corrects an earlier invoice; carries the opposite sign of what it reverses. */
    CREDIT_NOTE,
}
