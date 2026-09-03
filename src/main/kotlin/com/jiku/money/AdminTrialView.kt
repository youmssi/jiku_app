package com.jiku.money

import java.time.Instant
import java.util.UUID

/** One trial grant as the platform back-office sees it (JIKU-42). */
data class AdminTrialView(
    val id: UUID,
    val tenantId: String,
    val eventId: UUID,
    val tier: String,
    val grantedAllowance: Long,
    val expiresAt: Instant,
    val status: String,
    val endedReason: String?,
    val createdAt: Instant,
)
