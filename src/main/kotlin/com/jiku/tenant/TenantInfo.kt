package com.jiku.tenant

import java.time.Instant
import java.util.UUID

/**
 * Read-only view of a tenant, safe to share across module boundaries.
 */
data class TenantInfo(
    val id: UUID,
    val name: String,
    val contactEmail: String,
    val status: String,
    val createdAt: Instant,
    val displayName: String,
    val logoUrl: String?,
    val primaryColor: String,
)
