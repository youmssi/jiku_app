package jiku.tenantfixture

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TenantScopedTestRepository : JpaRepository<TenantScopedTestEntity, UUID>
