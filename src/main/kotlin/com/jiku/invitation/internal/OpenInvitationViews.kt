package com.jiku.invitation.internal

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant
import java.util.UUID

/** Platform limits of an open invitation (JIKU-184). */
@ConfigurationProperties(prefix = "open-invitation")
data class OpenInvitationProperties(
    /** Companions an organizer may allow per answer, at most. */
    val maxCompanions: Int = 10,
    /** Companions allowed on a new open invitation until the organizer changes it. */
    val defaultCompanions: Int = 5,
    /**
     * The Jikū WhatsApp number dedicated to cards (ADR 106), digits only, that a
     * card's "answer on WhatsApp" link opens. Blank: answers go through the web
     * page only.
     */
    val whatsappNumber: String = "",
    /**
     * The web app's public URL (JIKU-194): the WhatsApp card message shows the
     * card image it draws at `/api/cards/{code}`. Blank: the message has no image.
     */
    val cardImageBaseUrl: String = "",
)

data class OpenInvitationSettingsRequest(
    val enabled: Boolean = true,
    @field:Size(max = 500) val welcomeMessage: String? = null,
    @field:Min(0) val maxCompanions: Int? = null,
    val closesAt: Instant? = null,
    /** Free tier only: tell the people who answered, by WhatsApp, if the event is cancelled. Null keeps the current choice. */
    val notifyOnCancel: Boolean? = null,
)

data class OpenResponseRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotBlank @field:Size(max = 32) val phone: String,
    val answer: OpenAnswer,
    @field:Min(0) @field:Max(100) val companions: Int = 0,
)

/** Why an open invitation takes no answer right now; null in the views when it does. */
enum class OpenClosedReason {
    /** The organizer turned it off. */
    DISABLED,

    /** The closing date the organizer set has passed. */
    CLOSED,

    NOT_PUBLISHED,
    CANCELLED,

    /** The event is over. */
    ENDED,

    /** No place left under the event's capacity. */
    FULL,
}

data class OpenInvitationCounts(
    val yes: Int,
    val maybe: Int,
    val no: Int,
    /** People expected: every "yes" and its companions. */
    val expected: Int,
)

/** The organizer's view of an event's open invitation. */
data class OpenInvitationView(
    val eventId: UUID,
    val code: String,
    val enabled: Boolean,
    val welcomeMessage: String?,
    val maxCompanions: Int,
    val maxCompanionsAllowed: Int,
    val closesAt: Instant?,
    val accepting: Boolean,
    val closedReason: OpenClosedReason?,
    /** Places left under the event's capacity; null when it has none. */
    val remainingPlaces: Int?,
    val counts: OpenInvitationCounts,
    /** The organizer's choice to tell the people who answered, by WhatsApp, if the event is cancelled. */
    val notifyOnCancel: Boolean,
    /** Whether the event's tier includes that message, whatever the choice. */
    val cancelNoticeIncluded: Boolean,
    /** The dedicated WhatsApp number a card opens, digits only; null when answers go through the web only. */
    val whatsappNumber: String?,
)

/** What a person opening a shared card sees, before answering. */
data class PublicOpenInvitationView(
    val code: String,
    val eventName: String,
    val eventStart: Instant?,
    val eventEnd: Instant?,
    val eventTimezone: String,
    val eventLocation: String?,
    val welcomeMessage: String?,
    val organizerName: String,
    val logoUrl: String?,
    val primaryColor: String?,
    /** How the page, the card and the ticket look (JIKU-194): ELEGANT, MODERN or FESTIVE. */
    val cardStyle: String,
    /** The event's banner photo; null without one. */
    val bannerUrl: String?,
    /** The organizer's approved verification (COMPANY, PERSONAL) or null. */
    val organizerVerification: String?,
    val maxCompanions: Int,
    /** The last moment to answer, when the organizer set one. */
    val closesAt: Instant?,
    val accepting: Boolean,
    val closedReason: OpenClosedReason?,
    val whatsappNumber: String?,
)

/** A recorded answer, as the person who gave it sees it. */
data class OpenResponseView(
    val answer: OpenAnswer,
    val companions: Int,
    /** The signed link of the ticket page for a "yes"; null otherwise. */
    val ticketToken: String?,
)

/** A recorded answer, as the organizer sees it. */
data class OrganizerOpenResponseView(
    val id: UUID,
    val name: String,
    val phone: String,
    val answer: OpenAnswer,
    val companions: Int,
    val channel: OpenResponseChannel,
    val updatedAt: Instant,
)
