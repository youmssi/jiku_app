package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ServiceStaffRepository : JpaRepository<ServiceStaff, UUID> {
    fun findAllByServiceId(serviceId: UUID): List<ServiceStaff>
}
