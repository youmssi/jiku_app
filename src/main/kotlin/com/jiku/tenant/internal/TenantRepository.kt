package com.jiku.tenant.internal

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TenantRepository : JpaRepository<Tenant, UUID> {
    /**
     * Every active organization with its owner, consent and latest verification,
     * across tenants (JIKU-202). One row per owner; the caller keeps the first.
     */
    @Query(
        nativeQuery = true,
        value = """
        SELECT CAST(t.id AS VARCHAR), t.name, t.created_at, u.full_name, u.email,
               (SELECT v.phone FROM organization_verification v
                 WHERE v.tenant_id = CAST(t.id AS VARCHAR) ORDER BY v.submitted_at DESC LIMIT 1),
               COALESCE(u.marketing_consent, FALSE),
               (SELECT v.status FROM organization_verification v
                 WHERE v.tenant_id = CAST(t.id AS VARCHAR) ORDER BY v.submitted_at DESC LIMIT 1)
        FROM tenant t
        LEFT JOIN organizer_membership m ON m.tenant_id = CAST(t.id AS VARCHAR) AND m.role = 'OWNER'
        LEFT JOIN organizer_user u ON u.id = m.user_id
        WHERE t.status = 'ACTIVE'
        ORDER BY t.created_at DESC, m.created_at
        """,
    )
    fun findActivationProfiles(): List<Array<Any?>>

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
