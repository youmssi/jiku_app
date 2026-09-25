package com.jiku.messaging.internal

import java.util.UUID

/**
 * The ids carried by an interactive invitation's buttons (JIKU-143), which the
 * webhook receives back when the guest taps one: the answer and the invitation
 * it answers.
 */
object WhatsAppReplyPayload {
    private const val ACCEPT = "RSVP_YES:"
    private const val DECLINE = "RSVP_NO:"

    fun accept(invitationId: UUID): String = ACCEPT + invitationId

    fun decline(invitationId: UUID): String = DECLINE + invitationId

    /** The answer a button id stands for, or null when it is not one of ours. */
    fun parse(payload: String): Answer? {
        val accepted =
            when {
                payload.startsWith(ACCEPT) -> true
                payload.startsWith(DECLINE) -> false
                else -> return null
            }
        val id = payload.substringAfter(':')
        return runCatching { UUID.fromString(id) }.getOrNull()?.let { Answer(it, accepted) }
    }

    data class Answer(
        val invitationId: UUID,
        val accepted: Boolean,
    )
}
