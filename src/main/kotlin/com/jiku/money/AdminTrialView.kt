package com.jiku.money

import java.time.Instant
import java.util.UUID

/**
 * One trial grant as the platform back-office sees it (JIKU-42). [tenantName]
 * and [eventName] are populated only by the admin listing (JIKU-99) — they cost
 * a cross-tenant lookup per row, so the grant/end/sweep paths that already know
 * the tenant and event they're acting on leave them null rather than pay for a
 * lookup nobody reads.
 */
data class AdminTrialView(
    val id: UUID,
    val tenantId: String,
    val tenantName: String? = null,
    val eventId: UUID,
    val eventName: String? = null,
    val tier: String,
    val grantedAllowance: Long,
    val expiresAt: Instant,
    val status: String,
    val endedReason: String?,
    val createdAt: Instant,
)

/** One page of the back-office trial listing (JIKU-99), with the true total across all pages. */
data class AdminTrialPage(
    val entries: List<AdminTrialView>,
    val total: Long,
    val page: Int,
    val size: Int,
)

/**
 * Platform-wide trial funnel snapshot (JIKU-99): what the back-office overview
 * strip shows so an admin sees what needs attention without reading every row.
 * [conversionRatePercent] is null until at least one trial has ever concluded —
 * there is nothing yet to compute a rate from.
 */
data class AdminTrialStats(
    val active: Long,
    val expiringWithin7Days: Long,
    val convertedThisMonth: Long,
    val conversionRatePercent: Double?,
)
