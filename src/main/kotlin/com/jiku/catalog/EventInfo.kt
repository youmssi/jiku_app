package com.jiku.catalog

import java.time.Instant
import java.util.UUID

/**
 * Read-only view of an event shared across module boundaries. Times are UTC
 * instants; [timezone] is the event's IANA zone for presentation.
 */
data class EventInfo(
    val id: UUID,
    val tenantId: String,
    val name: String,
    val status: String,
    val startDateTime: Instant?,
    val endDateTime: Instant?,
    val timezone: String,
    val location: String?,
    /** Whether a confirmed guest may hand their place to someone else (JIKU-64). */
    val transferAllowed: Boolean = false,
    /** Instant after which transfers close; null means "until the event itself". */
    val transferDeadline: Instant? = null,
) {
    companion object {
        /** [status] value of a cancelled event, shared so consumers avoid magic strings. */
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

/** An event (with its owning tenant) whose data is due for retention anonymization. */
data class RetentionCandidate(
    val eventId: UUID,
    val tenantId: String,
)

/**
 * État du quorum d'un événement (JIKU-94), partagé au-delà du module.
 *
 * [reached] reflète l'instant présent ; [reachedAt] est la date de la **première**
 * atteinte et ne se réécrit jamais. Les deux peuvent diverger — des participants
 * repartis font retomber [reached] à faux alors que [reachedAt] demeure — et
 * c'est voulu : les deux informations sont vraies.
 */
data class QuorumInfo(
    val required: Long,
    val current: Long,
    val reached: Boolean,
    val reachedAt: java.time.Instant?,
)

/**
 * Une catégorie d'accès (JIKU-93), partagée au-delà du module : le portier doit
 * l'afficher, le tableau de bord doit la ventiler.
 */
data class TicketTypeInfo(
    val id: java.util.UUID,
    val label: String,
    val colorHex: String,
    val maxCapacity: Int?,
    val confirmedCount: Int,
)
