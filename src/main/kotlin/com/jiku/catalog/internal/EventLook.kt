package com.jiku.catalog.internal

import com.jiku.catalog.CardStyle
import com.jiku.shared.ApiProperties
import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

/** Limits and public address of event banner photos (JIKU-194). */
@ConfigurationProperties(prefix = "catalog.banner")
data class EventBannerProperties(
    /** This API's public URL: the banner's link, shown on public pages and in link previews. */
    val apiPublicUrl: String = "http://localhost:8080",
    /** The largest photo accepted; the browser resizes it well under this before upload. */
    val maxBytes: Int = 1_048_576,
    val minWidth: Int = 600,
    val minHeight: Int = 300,
)

/** An event's banner photo, stored next to the event. */
@Entity
@Table(name = "event_banner")
class EventBanner(
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "content", nullable = false)
    var content: ByteArray,
    @Column(name = "content_type", nullable = false, length = 32)
    var contentType: String,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) : BaseTenantEntity()

/** A banner as a public page reads it: before any tenant is bound. */
interface PublicBanner {
    val content: ByteArray
    val contentType: String
}

interface EventBannerRepository : JpaRepository<EventBanner, UUID> {
    /**
     * Native, so the tenant filter does not apply: a guest's page has no tenant.
     * A draft's banner is served too, so the organizer previews the card before
     * publishing; the event's id is random, and the photo is meant to be shared.
     */
    @Query(
        nativeQuery = true,
        value =
            "SELECT b.content AS content, b.content_type AS contentType FROM event_banner b WHERE b.event_id = :eventId",
    )
    fun findPublic(
        @Param("eventId") eventId: UUID,
    ): PublicBanner?
}

data class EventLookRequest(
    @field:NotNull val cardStyle: CardStyle?,
)

data class EventLookView(
    val cardStyle: CardStyle,
    /** The banner photo's public link; null without one. */
    val bannerUrl: String?,
)

/**
 * The look of an event's guest-facing surfaces (JIKU-194): the style the
 * organizer picked among Jikū's three, and an optional banner photo.
 */
@Service
class EventLookService(
    private val events: EventRepository,
    private val banners: EventBannerRepository,
    private val properties: EventBannerProperties,
    private val api: ApiProperties,
) {
    @Transactional(readOnly = true)
    fun look(eventId: UUID): EventLookView = load(eventId).toLook()

    @Transactional
    fun update(
        eventId: UUID,
        request: EventLookRequest,
    ): EventLookView {
        val event = load(eventId)
        event.cardStyle = requireNotNull(request.cardStyle)
        return events.save(event).toLook()
    }

    /** Stores [content] as the event's banner once it is checked to be a real photo of a usable size. */
    @Transactional
    fun replaceBanner(
        eventId: UUID,
        content: ByteArray,
        contentType: String?,
        now: Instant = Instant.now(),
    ): EventLookView {
        val event = load(eventId)
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type !in ACCEPTED_TYPES) {
            throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The photo must be a JPEG or PNG image")
        }
        if (content.isEmpty() || content.size > properties.maxBytes) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The photo must be at most ${properties.maxBytes} bytes")
        }
        val image =
            runCatching { ImageIO.read(ByteArrayInputStream(content)) }.getOrNull()
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This file is not a readable image")
        if (image.width < properties.minWidth || image.height < properties.minHeight) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The photo must be at least ${properties.minWidth} × ${properties.minHeight} pixels",
            )
        }
        val banner =
            banners.findById(eventId).orElse(null)?.apply {
                this.content = content
                this.contentType = requireNotNull(type)
                updatedAt = now
            } ?: EventBanner(eventId = eventId, content = content, contentType = requireNotNull(type), updatedAt = now)
        banners.save(banner)
        event.bannerUpdatedAt = now
        return events.save(event).toLook()
    }

    @Transactional
    fun removeBanner(eventId: UUID): EventLookView {
        val event = load(eventId)
        banners.findById(eventId).ifPresent { banners.delete(it) }
        event.bannerUpdatedAt = null
        return events.save(event).toLook()
    }

    @Transactional(readOnly = true)
    fun publicBanner(eventId: UUID): PublicBanner =
        banners.findPublic(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No banner")

    /** The banner's public link, versioned by its last change so it can be cached for good. */
    fun bannerUrl(event: Event): String? =
        event.bannerUpdatedAt?.let {
            "${properties.apiPublicUrl}${api.basePath}/public/events/${event.id}/banner?v=${it.epochSecond}"
        }

    private fun Event.toLook(): EventLookView = EventLookView(cardStyle = cardStyle, bannerUrl = bannerUrl(this))

    private fun load(eventId: UUID): Event =
        events.findById(eventId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found") }

    private companion object {
        val ACCEPTED_TYPES = setOf("image/jpeg", "image/png")
    }
}
