package com.jiku.shared

/**
 * Holds the tenant identifier bound to the current thread for the duration of a
 * request. It is populated early in the request lifecycle (from the authenticated
 * organizer's tenant once authentication exists) and read by
 * [TenantIdentifierResolver] so that Hibernate scopes every tenant-aware query and
 * insert automatically.
 *
 * This is the single source of truth for "who am I acting as" — no query should
 * derive the tenant any other way.
 */
object TenantContext {
    private val currentTenant = ThreadLocal<String?>()

    fun set(tenantId: String) = currentTenant.set(tenantId)

    fun get(): String? = currentTenant.get()

    fun clear() = currentTenant.remove()
}
