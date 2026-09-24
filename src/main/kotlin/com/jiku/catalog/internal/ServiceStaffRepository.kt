package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ServiceStaffRepository : JpaRepository<ServiceStaff, UUID> {
    fun findAllByServiceId(serviceId: UUID): List<ServiceStaff>

    /**
     * Cross-tenant lookup by short code (JIKU-88), for the public link-resolve
     * endpoint: the caller has no tenant bound yet — resolving the code is what
     * tells it which tenant to bind. Native SQL bypasses the Hibernate tenant
     * filter deliberately, the same exception used elsewhere for this reason.
     * Rows are (id, service_id, tenant_id, revoked); empty when the code is
     * unknown.
     */
    @Query(
        value = "SELECT CAST(id AS VARCHAR), CAST(service_id AS VARCHAR), tenant_id, revoked FROM service_staff WHERE code = :code",
        nativeQuery = true,
    )
    fun findRowByCode(
        @Param("code") code: String,
    ): List<Array<Any>>
}
