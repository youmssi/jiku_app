package com.jiku.shared

import org.hibernate.cfg.AvailableSettings
import org.hibernate.context.spi.CurrentTenantIdentifierResolver
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.stereotype.Component

/**
 * Bridges [TenantContext] to Hibernate's discriminator multi-tenancy. Hibernate
 * calls [resolveCurrentTenantIdentifier] when opening a session and uses the result
 * to scope tenant-aware reads and to stamp `tenant_id` on inserts.
 *
 * When no tenant is bound (background work, or a misconfigured request) it returns
 * [UNRESOLVED_TENANT] — a value that matches no real tenant, so reads return
 * nothing rather than leaking across tenants. Writes are additionally blocked by
 * the application guard in [BaseTenantEntity], so the sentinel never reaches a row.
 */
@Component
class TenantIdentifierResolver :
    CurrentTenantIdentifierResolver<String>,
    HibernatePropertiesCustomizer {
    override fun resolveCurrentTenantIdentifier(): String = TenantContext.get() ?: UNRESOLVED_TENANT

    override fun validateExistingCurrentSessions(): Boolean = false

    override fun customize(hibernateProperties: MutableMap<String, Any>) {
        hibernateProperties[AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER] = this
    }

    companion object {
        const val UNRESOLVED_TENANT = "__unresolved__"
    }
}
