package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * One priced line of an [Invoice]. Immutable for the same reason its header is:
 * an issued document is corrected by a credit note, never edited.
 *
 * [lineTotalMinor] is stored rather than derived so the document always renders
 * the arithmetic it was issued with, even if rounding rules are ever changed.
 */
@Entity
@Table(name = "invoice_line")
class InvoiceLine(
    @Column(name = "line_number", nullable = false, updatable = false)
    val lineNumber: Int,
    @Column(name = "description", nullable = false, updatable = false)
    val description: String,
    @Column(name = "quantity", nullable = false, updatable = false)
    val quantity: Long,
    @Column(name = "unit_price_minor", nullable = false, updatable = false)
    val unitPriceMinor: Long,
    @Column(name = "line_total_minor", nullable = false, updatable = false)
    val lineTotalMinor: Long,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
