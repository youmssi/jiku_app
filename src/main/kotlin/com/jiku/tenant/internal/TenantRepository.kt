package com.jiku.tenant.internal

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TenantRepository : JpaRepository<Tenant, UUID> {
    @Query(
        """
        SELECT t FROM Tenant t
        WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%'))
           OR LOWER(t.contactEmail) LIKE LOWER(CONCAT('%', :query, '%'))
        """,
    )
    fun search(
        query: String,
        pageable: Pageable,
    ): Page<Tenant>

    fun findByUsernameIgnoreCase(username: String): Tenant?
}
