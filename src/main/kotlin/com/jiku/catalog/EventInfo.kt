package com.jiku.catalog

import com.jiku.shared.ClientCharge
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
    /** Channels the organizer enabled on the event (EMAIL/WHATSAPP), enforced at send. */
    val invitationChannels: Set<InvitationChannel> = emptySet(),
) {
    companion object {
        /** [status] value of a draft event, shared so consumers avoid magic strings. */
        const val STATUS_DRAFT = "DRAFT"

        /** [status] value of a published event, shared so consumers avoid magic strings. */
        const val STATUS_PUBLISHED = "PUBLISHED"

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
 * Minimal cross-tenant event listing for the back-office (JIKU-42's trial desk):
 * enough to label an event in a picker or a table without exposing the full
 * [EventInfo] shape to callers outside the event's own tenant.
 */
data class EventSummary(
    val id: UUID,
    val name: String,
    val startDateTime: Instant?,
    val status: String,
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
    /** Price of a ticket sold in this category (JIKU-108); null when it is free. */
    val priceMinor: Long? = null,
    val currency: String? = null,
) {
    /** What a guest of this category owes the organization, or null when it is free. */
    fun clientCharge(): ClientCharge? = priceMinor?.let { ClientCharge(it, requireNotNull(currency)) }
}

/**
 * Question personnalisée d'un événement (JIKU-77), partagée au-delà du module :
 * l'invité doit la voir et y répondre au moment de confirmer.
 */
data class QuestionInfo(
    val id: java.util.UUID,
    val prompt: String,
    val required: Boolean,
)
