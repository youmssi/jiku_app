package com.jiku.shared

import com.jiku.shared.observability.MdcKeys
import org.slf4j.MDC

/**
 * Holds the tenant identifier bound to the current thread for the duration of a
 * request. It is populated early in the request lifecycle (from the authenticated
 * organizer's tenant once authentication exists) and read by
 * [TenantIdentifierResolver] so that Hibernate scopes every tenant-aware query and
 * insert automatically.
 *
 * This is the single source of truth for "who am I acting as" — no query should
 * derive the tenant any other way. Binding also mirrors the tenant into the MDC so
 * every log line emitted while acting as a tenant is attributed to it.
 */
object TenantContext {
    private val currentTenant = ThreadLocal<String?>()

    fun set(tenantId: String) {
        currentTenant.set(tenantId)
        MDC.put(MdcKeys.TENANT_ID, tenantId)
    }

    fun get(): String? = currentTenant.get()

    fun clear() {
        currentTenant.remove()
        MDC.remove(MdcKeys.TENANT_ID)
    }
}
