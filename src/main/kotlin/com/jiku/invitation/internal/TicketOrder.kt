package com.jiku.invitation.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Where a ticket order stands (JIKU-177). Places are held from
 * [AWAITING_PAYMENT] until the order is [PAID], [EXPIRED] or [REJECTED]; a
 * [DECLARED] order (the client says it paid) no longer expires, since only the
 * organization can tell whether the money arrived.
 */
enum class TicketOrderStatus {
    AWAITING_PAYMENT,
    DECLARED,
    PAID,
    EXPIRED,
    REJECTED,
    ;

    val holdsPlaces: Boolean
        get() = this == AWAITING_PAYMENT || this == DECLARED
}

/**
 * A client's purchase of tickets for an event (JIKU-177, plan de production 6.1).
 * The client pays the organization directly (Mobile Money or its payment link);
 * Jikū never touches the money. The tickets are issued only once the
 * organization confirms the payment, all in the buyer's name, each transferable.
 */
@Entity
@Table(name = "ticket_order")
class TicketOrder(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    /** Short code the client quotes to the organization, e.g. in the Mobile Money message. */
    @Column(name = "reference", nullable = false, updatable = false, length = 12)
    val reference: String,
    @Column(name = "buyer_name", nullable = false, length = 120)
    var buyerName: String,
    @Column(name = "buyer_phone", nullable = false, length = 32)
    var buyerPhone: String,
    @Column(name = "buyer_email", length = 254)
    var buyerEmail: String?,
    @Column(name = "total_minor", nullable = false, updatable = false)
    val totalMinor: Long,
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    var status: TicketOrderStatus = TicketOrderStatus.AWAITING_PAYMENT

    @Column(name = "declared_at")
    var declaredAt: Instant? = null

    /** The transaction reference the client gave when declaring its payment. */
    @Column(name = "payment_reference", length = 80)
    var paymentReference: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "rejection_reason", length = 300)
    var rejectionReason: String? = null
}

/** Tickets of one category in an order, at the price fixed when the order was placed. */
@Entity
@Table(name = "ticket_order_line")
class TicketOrderLine(
    @Column(name = "order_id", nullable = false, updatable = false)
    val orderId: UUID,
    @Column(name = "ticket_type_id", nullable = false, updatable = false)
    val ticketTypeId: UUID,
    @Column(name = "quantity", nullable = false, updatable = false)
    val quantity: Int,
    @Column(name = "unit_price_minor", nullable = false, updatable = false)
    val unitPriceMinor: Long,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}

interface TicketOrderRepository : JpaRepository<TicketOrder, UUID> {
    fun findByEventIdOrderByCreatedAtDesc(eventId: UUID): List<TicketOrder>

    fun findByEventIdAndStatusOrderByCreatedAtDesc(
        eventId: UUID,
        status: TicketOrderStatus,
    ): List<TicketOrder>

    fun existsByReference(reference: String): Boolean

    /**
     * Moves an order from one of [from] to [to] in one conditional write, so two
     * operators confirming at once, or a confirmation racing the expiry sweep,
     * can never both win: the loser updates nothing and is told so.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE TicketOrder o SET o.status = :to, o.decidedAt = :at, o.decidedBy = :by " +
            "WHERE o.id = :id AND o.status IN :from",
    )
    fun transition(
        @Param("id") id: UUID,
        @Param("from") from: Collection<TicketOrderStatus>,
        @Param("to") to: TicketOrderStatus,
        @Param("at") at: Instant,
        @Param("by") by: String?,
    ): Int

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE TicketOrder o SET o.status = com.jiku.invitation.internal.TicketOrderStatus.DECLARED, " +
            "o.declaredAt = :at, o.paymentReference = :reference WHERE o.id = :id " +
            "AND o.status = com.jiku.invitation.internal.TicketOrderStatus.AWAITING_PAYMENT AND o.expiresAt > :at",
    )
    fun declare(
        @Param("id") id: UUID,
        @Param("reference") reference: String,
        @Param("at") at: Instant,
    ): Int

    /** Unpaid orders past their hold in this tenant; the sweep expires them one by one. */
    @Query(
        "SELECT o.id FROM TicketOrder o WHERE o.status = com.jiku.invitation.internal.TicketOrderStatus.AWAITING_PAYMENT " +
            "AND o.expiresAt <= :now",
    )
    fun findExpiredIds(
        @Param("now") now: Instant,
    ): List<UUID>

    /** Tenants with at least one unpaid order past its hold: cross-tenant, like the other sweeps. */
    @Query(
        value = "SELECT DISTINCT tenant_id FROM ticket_order WHERE status = 'AWAITING_PAYMENT' AND expires_at <= :now",
        nativeQuery = true,
    )
    fun expiredTenantIds(
        @Param("now") now: Instant,
    ): List<String>
}

interface TicketOrderLineRepository : JpaRepository<TicketOrderLine, UUID> {
    fun findByOrderId(orderId: UUID): List<TicketOrderLine>

    fun findByOrderIdIn(orderIds: Collection<UUID>): List<TicketOrderLine>
}
