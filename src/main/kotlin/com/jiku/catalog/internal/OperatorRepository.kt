package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OperatorRepository : JpaRepository<Operator, UUID> {
    @EntityGraph(attributePaths = ["eventIds", "serviceIds"])
    fun findWithScopeById(id: UUID): Operator?

    @EntityGraph(attributePaths = ["eventIds", "serviceIds"])
    @Query("SELECT o FROM Operator o ORDER BY o.createdAt")
    fun findAllWithScope(): List<Operator>

    @EntityGraph(attributePaths = ["eventIds", "serviceIds"])
    @Query("SELECT o FROM Operator o WHERE :eventId MEMBER OF o.eventIds ORDER BY o.createdAt")
    fun findByEvent(
        @Param("eventId") eventId: UUID,
    ): List<Operator>

    @EntityGraph(attributePaths = ["eventIds", "serviceIds"])
    @Query("SELECT o FROM Operator o WHERE :serviceId MEMBER OF o.serviceIds ORDER BY o.createdAt")
    fun findByService(
        @Param("serviceId") serviceId: UUID,
    ): List<Operator>

    /**
     * Cross-tenant lookup by short code, for the public code-resolve endpoints:
     * the caller has no tenant bound yet — resolving the code is what tells it
     * which tenant to bind. Native SQL bypasses the Hibernate tenant filter
     * deliberately. Rows are (id, tenant_id, revoked); empty when the code is
     * unknown.
     */
    @Query(
        value = "SELECT CAST(id AS VARCHAR), tenant_id, revoked FROM operator WHERE code = :code",
        nativeQuery = true,
    )
    fun findRowByCode(
        @Param("code") code: String,
    ): List<Array<Any>>
}
