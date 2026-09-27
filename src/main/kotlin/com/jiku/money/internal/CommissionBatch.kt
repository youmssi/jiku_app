package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Commission on tickets sold (JIKU-178); every figure is configuration. */
@ConfigurationProperties(prefix = "sales.commission")
data class CommissionProperties(
    val rate: BigDecimal = BigDecimal("0.03"),
    val batchSize: Int = 50,
    val creditValidity: Duration = Duration.ofDays(365),
)

/** How a batch was funded. */
enum class CommissionFunding {
    /** The organization's first batch, offered (ADR 105). */
    FREE,

    /** Paid before selling. */
    PAID,

    /** Opened without paying, by a verified organization; settled with its next payment. */
    CREDIT,

    /** Tickets sold beyond the batches on the event day, when a sale never pauses; owed after. */
    OVERAGE,
}

enum class CommissionBatchStatus {
    PENDING_PAYMENT,
    ACTIVE,
    CLOSED,
    CANCELLED,
}

/**
 * Commission paid ahead for the next tickets of one category (ADR 104 §8):
 * [size] tickets at [unitCommissionMinor] each. Every ticket sold consumes one
 * place; once the event is over the batch closes. What a paid batch did not use
 * becomes a credit; what a credit or overage batch used is [owedMinor].
 */
@Entity
@Table(name = "commission_batch")
class CommissionBatch(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "ticket_type_id", nullable = false, updatable = false)
    val ticketTypeId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "funding", nullable = false, length = 16)
    var funding: CommissionFunding,
    @Column(name = "size", nullable = false)
    var size: Int,
    @Column(name = "unit_commission_minor", nullable = false, updatable = false)
    val unitCommissionMinor: Long,
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    val currency: String,
    @Column(name = "closes_at", nullable = false, updatable = false)
    val closesAt: Instant,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    var status: CommissionBatchStatus = CommissionBatchStatus.ACTIVE

    @Column(name = "consumed", nullable = false)
    var consumed: Int = 0

    /** What the batch costs: its places at the unit commission. */
    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long = 0

    /** Still owed to Jikū for a credit or overage batch; zero once settled. */
    @Column(name = "owed_minor", nullable = false)
    var owedMinor: Long = 0

    /** Credit deducted from what this batch's payment asked. */
    @Column(name = "credit_applied_minor", nullable = false)
    var creditAppliedMinor: Long = 0

    @Column(name = "payment_id")
    var paymentId: UUID? = null

    @Column(name = "settled_at")
    var settledAt: Instant? = null

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    val remaining: Int
        get() = (size - consumed).coerceAtLeast(0)
}

/** The unused part of a paid batch, deducted from the organization's next commission payments (ADR 104 §8). */
@Entity
@Table(name = "commission_credit")
class CommissionCredit(
    @Column(name = "source_batch_id", nullable = false, updatable = false)
    val sourceBatchId: UUID,
    @Column(name = "amount_minor", nullable = false, updatable = false)
    val amountMinor: Long,
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    val currency: String,
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "remaining_minor", nullable = false)
    var remainingMinor: Long = amountMinor
}

interface CommissionBatchRepository : JpaRepository<CommissionBatch, UUID> {
    fun findByEventIdOrderByCreatedAtAsc(eventId: UUID): List<CommissionBatch>

    /**
     * The category's live batches, oldest first, locked until the caller's
     * transaction ends: two confirmations consuming the last places of a batch
     * at once are serialized, so a place is never consumed twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT b FROM CommissionBatch b WHERE b.eventId = :eventId AND b.ticketTypeId = :ticketTypeId " +
            "AND b.status = com.jiku.money.internal.CommissionBatchStatus.ACTIVE ORDER BY b.createdAt ASC",
    )
    fun lockActive(
        @Param("eventId") eventId: UUID,
        @Param("ticketTypeId") ticketTypeId: UUID,
    ): List<CommissionBatch>

    fun existsByFundingIn(funding: Collection<CommissionFunding>): Boolean

    fun existsByFunding(funding: CommissionFunding): Boolean

    /** Batches whose commission is still owed: credit batches not yet settled, and overage. */
    @Query(
        "SELECT b FROM CommissionBatch b WHERE b.funding IN (com.jiku.money.internal.CommissionFunding.CREDIT, " +
            "com.jiku.money.internal.CommissionFunding.OVERAGE) AND b.settledAt IS NULL " +
            "AND b.status <> com.jiku.money.internal.CommissionBatchStatus.CANCELLED",
    )
    fun findUnsettled(): List<CommissionBatch>

    @Query(
        "SELECT b.id FROM CommissionBatch b WHERE b.closesAt <= :now AND b.status IN " +
            "(com.jiku.money.internal.CommissionBatchStatus.ACTIVE, com.jiku.money.internal.CommissionBatchStatus.PENDING_PAYMENT)",
    )
    fun findDueToClose(
        @Param("now") now: Instant,
    ): List<UUID>

    /** Tenants with batches of a past event still open: cross-tenant, like the other sweeps. */
    @Query(
        value =
            "SELECT DISTINCT tenant_id FROM commission_batch WHERE closes_at <= :now " +
                "AND status IN ('ACTIVE', 'PENDING_PAYMENT')",
        nativeQuery = true,
    )
    fun tenantsDueToClose(
        @Param("now") now: Instant,
    ): List<String>
}

interface CommissionCreditRepository : JpaRepository<CommissionCredit, UUID> {
    @Query("SELECT c FROM CommissionCredit c WHERE c.remainingMinor > 0 AND c.expiresAt > :now ORDER BY c.expiresAt ASC")
    fun findUsable(
        @Param("now") now: Instant,
    ): List<CommissionCredit>
}
