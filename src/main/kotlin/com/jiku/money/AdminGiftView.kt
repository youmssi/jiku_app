package com.jiku.money

import java.time.Instant
import java.util.UUID

/** What the back office offers (JIKU-315); the fields that do not apply to [kind] stay null. */
data class OfferGiftCommand(
    val tenantId: UUID,
    /** EVENT_GUESTS, SERVICES_PLAN or ORGANIZER_PACK. */
    val kind: String,
    val guests: Long?,
    val days: Int?,
    val plan: String?,
    val months: Int?,
    val eventId: UUID?,
    val message: String?,
    /** When the offer lapses; null takes the default claim window. */
    val claimBy: Instant?,
    /** Applies the gift at once instead of waiting for the organizer. */
    val applyNow: Boolean,
    val createdBy: String?,
)

/** One gift as the back office sees it (JIKU-315). */
data class AdminGiftView(
    val id: UUID,
    val tenantId: String,
    val tenantName: String? = null,
    val kind: String,
    val guests: Long?,
    val days: Int?,
    val plan: String?,
    val months: Int?,
    val eventId: UUID?,
    val eventName: String? = null,
    val message: String?,
    val claimBy: Instant,
    val status: String,
    val activatedAt: Instant?,
    val activatedEventId: UUID?,
    val revokedReason: String?,
    val createdBy: String?,
    val createdAt: Instant,
)

data class AdminGiftPage(
    val entries: List<AdminGiftView>,
    val total: Long,
    val page: Int,
    val size: Int,
)
