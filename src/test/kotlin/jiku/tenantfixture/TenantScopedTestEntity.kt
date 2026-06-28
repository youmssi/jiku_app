package jiku.tenantfixture

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * Test-only entity used to exercise the tenant filter. It lives outside the
 * `com.jiku` package on purpose, so it is not picked up by the application's
 * default entity scan and therefore does not affect other tests; the tenant
 * isolation test scans it explicitly.
 */
@Entity
@Table(name = "tenant_scoped_test_entity")
class TenantScopedTestEntity(
    @Column(name = "name", nullable = false)
    var name: String = "",
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
