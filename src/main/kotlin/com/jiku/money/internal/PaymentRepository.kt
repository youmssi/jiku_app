package com.jiku.money.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PaymentRepository : JpaRepository<Payment, UUID> {
    /** First confirmed payment per organization, across tenants, for the back-office follow-up list (JIKU-202). */
    @Query(
        nativeQuery = true,
        value = "SELECT tenant_id, MIN(updated_at) FROM payment WHERE status = 'SUCCEEDED' GROUP BY tenant_id",
    )
    fun firstSucceededAtByTenant(): List<Array<Any?>>

    /** The current tenant's payments, newest first (billing history — JIKU-35). */
    fun findByOrderByCreatedAtDesc(): List<Payment>

    fun findByEventIdOrderByCreatedAtDesc(eventId: UUID): List<Payment>

    /** Open manual request for the event+tier, for idempotent re-requests (JIKU-41). */
    fun findFirstByEventIdAndTierAndProviderAndStatusOrderByCreatedAtDesc(
        eventId: UUID,
        tier: String,
        provider: String,
        status: PaymentStatus,
    ): Payment?

    /** Open manual prepaid-subscription request (JIKU-90), for idempotent re-requests. */
    fun findFirstByKindAndProviderAndStatusOrderByCreatedAtDesc(
        kind: String,
        provider: String,
        status: PaymentStatus,
    ): Payment?

    /**
     * Platform-admin payments desk (JIKU-41): a deliberately cross-tenant read.
     * Native SQL because the Hibernate discriminator filter scopes every
     * entity/JPQL query to the bound tenant, and the back-office runs with no
     * tenant bound. Read-only; mutations always rebind the payment's tenant first.
     */
    @Query(
        value = """
            SELECT * FROM payment
            WHERE (:status IS NULL OR status = :status)
              AND (:provider IS NULL OR provider = :provider)
              AND (:tenantId IS NULL OR tenant_id = :tenantId)
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
        """,
        nativeQuery = true,
    )
    fun adminList(
        @Param("status") status: String?,
        @Param("provider") provider: String?,
        @Param("tenantId") tenantId: String?,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<Payment>

    /**
     * Payments still PENDING with one of [providers], started between [after] and
     * [before], across tenants (JIKU-272): their callback may have been lost.
     * Native SQL for the same reason as [adminList]; each row is then settled
     * under its own tenant.
     */
    @Query(
        value = """
            SELECT id, tenant_id FROM payment
            WHERE status = 'PENDING' AND provider IN (:providers)
              AND created_at < :before AND created_at > :after
            ORDER BY created_at
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findPendingToReconcile(
        @Param("providers") providers: Collection<String>,
        @Param("before") before: Instant,
        @Param("after") after: Instant,
        @Param("limit") limit: Int,
    ): List<Array<Any?>>

    /** The current tenant's settled payments with no invoice yet (JIKU-277), oldest first. */
    @Query(
        "SELECT p.id FROM Payment p WHERE p.status = com.jiku.money.internal.PaymentStatus.SUCCEEDED " +
            "AND NOT EXISTS (SELECT 1 FROM Invoice i WHERE i.paymentId = p.id) ORDER BY p.createdAt",
    )
    fun findSucceededWithoutInvoice(): List<UUID>

    /** Tenant owning a payment, for rebinding context before an admin mutation. */
    @Query(value = "SELECT tenant_id FROM payment WHERE id = :id", nativeQuery = true)
    fun findTenantIdById(
        @Param("id") id: UUID,
    ): String?
}
