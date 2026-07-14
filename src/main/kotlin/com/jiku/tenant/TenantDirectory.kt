package com.jiku.tenant

import java.time.Instant
import java.util.UUID

/**
 * One page of the platform-admin tenant directory (JIKU-40). Read-only, safe to
 * cross module boundaries.
 */
data class TenantDirectoryPage(
    val entries: List<TenantDirectoryEntry>,
    val total: Long,
    val page: Int,
    val size: Int,
)

data class TenantDirectoryEntry(
    val id: UUID,
    val name: String,
    val contactEmail: String,
    val status: String,
    val createdAt: Instant,
    val organizerCount: Long,
)
