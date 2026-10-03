package com.jiku.invitation.internal

import com.jiku.shared.BaseTenantEntity
import com.jiku.shared.LiveScoped
import com.jiku.shared.LiveTopics
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * An event's open invitation (JIKU-184, ADR 106): shared in groups without a
 * guest list, reached by its short public [code]. One per event.
 */
@Entity
@Table(name = "open_invitation")
class OpenInvitation(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "code", nullable = false, updatable = false)
    val code: String,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,
    @Column(name = "welcome_message")
    var welcomeMessage: String? = null,
    @Column(name = "max_companions", nullable = false)
    var maxCompanions: Int,
    @Column(name = "closes_at")
    var closesAt: Instant? = null,
    /** On the free tier, whether the people who answered are told by WhatsApp when the event is cancelled. */
    @Column(name = "notify_on_cancel", nullable = false)
    var notifyOnCancel: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}

/** Where a public code leads: read before any tenant is bound, so outside the tenant filter. */
interface OpenInvitationRoute {
    val tenantId: String
    val eventId: UUID
}

interface OpenInvitationRepository : JpaRepository<OpenInvitation, UUID> {
    fun findByEventId(eventId: UUID): OpenInvitation?

    fun existsByCode(code: String): Boolean

    /**
     * Native, so the tenant filter does not apply: a visitor opening a shared
     * card has no tenant yet. It returns only the routing, the caller binds the
     * tenant before reading anything else.
     */
    @Query(
        nativeQuery = true,
        value = "SELECT tenant_id AS tenantId, event_id AS eventId FROM open_invitation WHERE code = :code",
    )
    fun route(
        @Param("code") code: String,
    ): OpenInvitationRoute?
}

/**
 * One person's answer to an open invitation (JIKU-184). One per number and
 * event, changed in place when the person answers again.
 */
@Entity
@Table(name = "open_response")
class OpenResponse(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "phone", nullable = false)
    var phone: String,
    @Column(name = "name", nullable = false)
    var name: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "answer", nullable = false, length = 8)
    var answer: OpenAnswer,
    @Column(name = "companions", nullable = false)
    var companions: Int = 0,
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    var channel: OpenResponseChannel,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : BaseTenantEntity(),
    LiveScoped {
    override fun liveTopics(): Collection<String> = listOf(LiveTopics.event(eventId))

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    /** The most people this answer ever counted against the guest tier; never counted twice. */
    @Column(name = "committed_heads", nullable = false)
    var committedHeads: Int = 0

    /** The guest holding the ticket, once the person said yes. */
    @Column(name = "guest_id")
    var guestId: UUID? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt

    @Column(name = "removed_at")
    var removedAt: Instant? = null

    /** People this answer brings: the person and their companions for a yes, none otherwise. */
    val heads: Int
        get() = if (answer == OpenAnswer.YES) 1 + companions else 0

    /**
     * Removes the name and the number (JIKU-36, JIKU-37) and keeps the rest, so
     * the counts and the people already counted against the tier stay right.
     */
    fun erase() {
        name = ERASED_NAME
        phone = ERASED_PHONE_PREFIX + requireNotNull(id).toString().replace("-", "").take(ERASED_PHONE_LENGTH)
    }

    val erased: Boolean
        get() = phone.startsWith(ERASED_PHONE_PREFIX)

    private companion object {
        const val ERASED_NAME = "Deleted Guest"
        const val ERASED_PHONE_PREFIX = "E"
        const val ERASED_PHONE_LENGTH = 19
    }
}

enum class OpenAnswer {
    YES,
    MAYBE,
    NO,
}

enum class OpenResponseChannel {
    WEB,
    WHATSAPP,
}

interface OpenResponseRepository : JpaRepository<OpenResponse, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByEventIdAndPhone(
        eventId: UUID,
        phone: String,
    ): OpenResponse?

    fun findByEventIdOrderByUpdatedAtDesc(eventId: UUID): List<OpenResponse>

    fun findByIdAndEventId(
        id: UUID,
        eventId: UUID,
    ): OpenResponse?

    @Query("SELECT COALESCE(SUM(r.committedHeads), 0) FROM OpenResponse r WHERE r.eventId = :eventId")
    fun committedHeads(
        @Param("eventId") eventId: UUID,
    ): Long

    fun findByGuestId(guestId: UUID): List<OpenResponse>
}
