package com.jiku.backoffice.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A negotiated enterprise agreement (JIKU-43): a custom SaaS deal or an
 * on-premise license, with a validity period and renewal date. Platform-level
 * metadata about a tenant (like the audit log), so deliberately not
 * tenant-scoped. Renewal never mutates the period in place — it closes this row
 * as RENEWED and opens a new one, so the history of periods is the rows
 * themselves. Amounts are informational: collection stays manual/invoiced.
 */
@Entity
@Table(name = "agreement")
class Agreement(
    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    val kind: AgreementKind,
    @Column(name = "period_start", nullable = false)
    val periodStart: Instant,
    @Column(name = "period_end", nullable = false)
    val periodEnd: Instant,
    @Column(name = "renewal_at", nullable = false)
    val renewalAt: Instant,
    @Column(name = "amount_minor")
    val amountMinor: Long?,
    @Column(name = "currency")
    val currency: String?,
    @Column(name = "notes")
    var notes: String?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: AgreementStatus = AgreementStatus.ACTIVE

    /** Why the agreement was interrupted; null unless [status] is INTERRUPTED. */
    @Column(name = "interrupted_reason")
    var interruptedReason: String? = null

    /** The row that replaced this one on renewal; null unless [status] is RENEWED. */
    @Column(name = "renewed_by")
    var renewedBy: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

enum class AgreementKind {
    ENTERPRISE_SAAS,
    ON_PREMISE,
}

/**
 * EXPIRED is set by the sweep once the period ends (a human decides what
 * follows); INTERRUPTED is a deliberate stop that also suspends the tenant for
 * ENTERPRISE_SAAS; RENEWED rows are the preserved history of past periods.
 */
enum class AgreementStatus {
    ACTIVE,
    EXPIRED,
    INTERRUPTED,
    RENEWED,
}
