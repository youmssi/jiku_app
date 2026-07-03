package com.jiku.checkin.internal

import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Organizer request to mint a validator access link, optionally labeled. */
data class CreateValidatorRequest(
    @field:Size(max = 255)
    val label: String? = null,
)

/**
 * A validator access link as seen by the organizer. [link] is the shareable URL;
 * a revoked link is returned for transparency but no longer authorizes check-in.
 */
data class ValidatorResponse(
    val id: UUID,
    val label: String,
    val link: String,
    val revoked: Boolean,
    val createdAt: Instant,
    val revokedAt: Instant?,
)

/**
 * Context shown to a validator on opening their link: the event being staffed,
 * the organizer's branding, the link's label, and live attendance counters.
 * Times are UTC instants rendered in [timezone] by the client.
 */
data class ValidatorContextResponse(
    val eventName: String,
    /** The event's lifecycle status; CANCELLED means check-in is closed for good. */
    val eventStatus: String,
    val startDateTime: Instant?,
    val timezone: String,
    val eventLocation: String?,
    val organizerName: String,
    val primaryColor: String,
    val logoUrl: String?,
    val validatorLabel: String,
    val checkedIn: Long,
    val confirmed: Long,
)
