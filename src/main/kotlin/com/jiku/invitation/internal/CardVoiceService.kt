package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.shared.ApiProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * The host's voice message on their card (JIKU-284, decision D1: free, 30
 * seconds at most). The organizer records or uploads it; guests fetch it only
 * when they tap play, so the card page itself stays light.
 */
@Service
class CardVoiceService(
    private val voices: CardVoiceRepository,
    private val openInvitations: OpenInvitationRepository,
    private val events: EventModuleApi,
    private val properties: OpenInvitationProperties,
    private val api: ApiProperties,
) {
    @Transactional
    fun replace(
        eventId: UUID,
        content: ByteArray,
        durationSeconds: Int,
        transcript: String?,
        now: Instant = Instant.now(),
    ): CardVoiceView {
        val invitation = requireCard(eventId)
        if (durationSeconds !in 1..properties.voiceMaxSeconds) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The voice message must last 1 to ${properties.voiceMaxSeconds} seconds")
        }
        val type = acceptVoice(content, properties.voiceMaxBytes)
        val words = transcript?.trim()?.takeIf { it.isNotEmpty() }
        if (words != null && words.length > TRANSCRIPT_MAX) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The transcript must be at most $TRANSCRIPT_MAX characters")
        }
        val voice =
            voices.findById(eventId).orElse(null)?.apply {
                this.content = content
                this.contentType = type
                this.durationSeconds = durationSeconds
                this.transcript = words
                this.updatedAt = now
            } ?: CardVoice(eventId, content, type, durationSeconds, words, now)
        return view(voices.save(voice), invitation.code)
    }

    @Transactional
    fun remove(eventId: UUID) {
        requireCard(eventId)
        voices.deleteById(eventId)
    }

    /** The voice message of a card, for its pages; null without one. */
    @Transactional(readOnly = true)
    fun view(
        eventId: UUID,
        code: String,
    ): CardVoiceView? = voices.findById(eventId).orElse(null)?.let { view(it, code) }

    /** The recording itself, under the card's tenant. */
    @Transactional(readOnly = true)
    fun recording(eventId: UUID): CardVoice =
        voices.findById(eventId).orElse(null) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This card has no voice message")

    private fun view(
        voice: CardVoice,
        code: String,
    ) = CardVoiceView(
        url = "${properties.apiPublicUrl}${api.basePath}/open/$code/voice?v=${voice.updatedAt.epochSecond}",
        durationSeconds = voice.durationSeconds,
        transcript = voice.transcript,
    )

    private fun requireCard(eventId: UUID): OpenInvitation {
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        return openInvitations.findByEventId(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No open invitation")
    }

    private companion object {
        const val TRANSCRIPT_MAX = 500
    }
}
