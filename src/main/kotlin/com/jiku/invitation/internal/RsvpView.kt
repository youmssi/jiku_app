package com.jiku.invitation.internal

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class RsvpView(
    val eventName: String,
    val eventWhen: String?,
    val eventLocation: String?,
    val organizerName: String,
    val primaryColor: String,
    val logoUrl: String?,
    val guestName: String,
    val status: String,
    val ticketCode: String?,
    /** The event's own lifecycle status; CANCELLED renders a cancellation notice. */
    val eventStatus: String? = null,
    /** True once the guest has erased their personal data (JIKU-36). */
    val erased: Boolean = false,
    /**
     * True when this guest can hand their place to someone else right now
     * (JIKU-64): the event allows transfers, the deadline has not passed, they are
     * confirmed, and they have not already been checked in. The UI shows the
     * transfer affordance only when this is true, and the endpoint re-checks every
     * condition regardless of what the UI decided.
     */
    val transferAllowed: Boolean = false,
    /** When transfers close, for display alongside the affordance. */
    val transferDeadline: Instant? = null,
    /** Name of the person this place was handed to, once transferred. */
    val transferredTo: String? = null,
    /** Questions personnalisées posées au moment de confirmer (JIKU-77). */
    val questions: List<RsvpQuestion> = emptyList(),
)

/** Question à répondre lors de la confirmation ; réponse libre attendue. */
data class RsvpQuestion(
    val questionId: java.util.UUID,
    val prompt: String,
    val required: Boolean,
)

/**
 * Recipient of a transferred place (JIKU-64). At least one contact channel is
 * required — the recipient needs to receive their own invitation link, and which
 * channel that is depends on what the sender knows about them.
 */
data class TransferTicketRequest(
    @field:NotBlank(message = "The recipient's first name is required")
    @field:Size(max = 100)
    val firstName: String,
    @field:NotBlank(message = "The recipient's last name is required")
    @field:Size(max = 100)
    val lastName: String,
    @field:Email(message = "Enter a valid email address")
    @field:Size(max = 255)
    val email: String? = null,
    @field:Size(max = 32)
    val phoneNumber: String? = null,
)
