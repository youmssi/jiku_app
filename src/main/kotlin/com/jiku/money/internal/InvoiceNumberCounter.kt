package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

/**
 * The next invoice number for one tenant's fiscal year (JIKU-69).
 *
 * Deliberately a table row rather than a PostgreSQL sequence. A sequence keeps
 * advancing when the surrounding transaction rolls back, leaving holes in the
 * numbering — and a numbering sequence with holes is exactly what a tax audit
 * asks about. This row is incremented inside the issuing transaction under a
 * pessimistic lock, so a rollback returns the number and concurrent issuers
 * serialise behind each other.
 */
@Entity
@Table(
    name = "invoice_number_counter",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_invoice_counter_tenant_year", columnNames = ["tenant_id", "fiscal_year"]),
    ],
)
class InvoiceNumberCounter(
    @Column(name = "fiscal_year", nullable = false, updatable = false)
    val fiscalYear: Int,
    @Column(name = "next_number", nullable = false)
    var nextNumber: Long,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
