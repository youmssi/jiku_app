package com.jiku.shared

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import org.hibernate.annotations.TenantId

/**
 * Mapped superclass for every tenant-scoped entity. It contributes the
 * `tenant_id` column and, via Hibernate's [TenantId], makes Hibernate
 * automatically populate it on insert from [TenantContext] and append a
 * `tenant_id = :current` predicate to every read — without any repository needing
 * a manual `WHERE tenant_id = ?` clause.
 *
 * Two independent guards ensure there is no path to tenant-less data:
 *  - the column is `NOT NULL` (database guard), and
 *  - [requireTenantContext] rejects a persist attempted with no active tenant
 *    (application guard), so data is never written under the unresolved sentinel.
 */
@MappedSuperclass
abstract class BaseTenantEntity {
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: String? = null
        protected set

    @PrePersist
    protected fun requireTenantContext() {
        check(TenantContext.get() != null) {
            "Cannot persist a tenant-scoped entity without an active tenant context"
        }
    }
}
