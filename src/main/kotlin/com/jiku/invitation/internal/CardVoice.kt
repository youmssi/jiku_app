package com.jiku.invitation.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/** The host's voice message on their card (JIKU-284): a short recording guests play on demand. */
@Entity
@Table(name = "open_card_voice")
class CardVoice(
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "content", nullable = false)
    var content: ByteArray,
    @Column(name = "content_type", nullable = false, length = 32)
    var contentType: String,
    @Column(name = "duration_seconds", nullable = false)
    var durationSeconds: Int,
    /** What the host says, typed by them, for guests who cannot listen. */
    @Column(name = "transcript", length = 500)
    var transcript: String?,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) : BaseTenantEntity()

interface CardVoiceRepository : JpaRepository<CardVoice, UUID>

/** The voice message as a page shows it: where to play it, how long it lasts and its words. */
data class CardVoiceView(
    val url: String,
    val durationSeconds: Int,
    val transcript: String?,
)

/**
 * Checks that [content] really is a recording a phone makes or plays (WebM or Ogg
 * Opus, MP4 or M4A, MP3) from its first bytes, not from the name or the type the
 * browser claims, and returns the media type to serve it with.
 */
fun acceptVoice(
    content: ByteArray,
    maxBytes: Int,
): String {
    if (content.isEmpty() || content.size > maxBytes) {
        throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The voice message must be at most $maxBytes bytes")
    }

    fun startsWith(vararg bytes: Int) = bytes.indices.all { content.size > it && (content[it].toInt() and 0xff) == bytes[it] }

    fun at(
        offset: Int,
        text: String,
    ) = content.size >= offset + text.length && String(content, offset, text.length, Charsets.ISO_8859_1) == text
    return when {
        startsWith(0x1A, 0x45, 0xDF, 0xA3) -> "audio/webm"
        at(0, "OggS") -> "audio/ogg"
        at(4, "ftyp") -> "audio/mp4"
        at(0, "ID3") || startsWith(0xFF, 0xFB) || startsWith(0xFF, 0xF3) -> "audio/mpeg"
        else -> throw ResponseStatusException(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "The voice message must be a WebM, Ogg, MP4 or MP3 recording",
        )
    }
}
