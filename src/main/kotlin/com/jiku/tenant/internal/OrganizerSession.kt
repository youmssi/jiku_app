package com.jiku.tenant.internal

import com.jiku.shared.SessionAccessGate
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One signed-in session of an organizer account (JIKU-317): every token of the
 * session names it, so closing it here stops its tokens on their next request.
 */
@Entity
@Table(name = "organizer_session")
class OrganizerSession(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,
    @Column(name = "device_id", updatable = false, length = 64)
    val deviceId: String?,
    @Column(name = "device", updatable = false, length = 200)
    val device: String?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "last_used_at", nullable = false)
    var lastUsedAt: Instant = createdAt

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
}

interface OrganizerSessionRepository : JpaRepository<OrganizerSession, UUID> {
    fun findByUserIdAndRevokedAtIsNullAndLastUsedAtAfterOrderByLastUsedAtDesc(
        userId: UUID,
        after: Instant,
    ): List<OrganizerSession>

    fun findByUserIdAndRevokedAtIsNull(userId: UUID): List<OrganizerSession>

    fun existsByUserId(userId: UUID): Boolean

    fun existsByUserIdAndDeviceId(
        userId: UUID,
        deviceId: String,
    ): Boolean
}

/** How long a session unused for a while is still listed as signed in (JIKU-317). */
@ConfigurationProperties(prefix = "auth.sessions")
data class SessionProperties(
    val idleListing: Duration = Duration.ofDays(7),
)

/**
 * The tenant module's answer to [SessionAccessGate]: consulted on every request
 * whose token names a session, so answers are cached briefly and closing a
 * session forgets its entry at once.
 */
@Component
class SessionAccessGateAdapter(
    private val sessions: OrganizerSessionRepository,
) : SessionAccessGate {
    private data class Cached(
        val open: Boolean,
        val expiresAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun isOpen(sessionId: String): Boolean {
        val now = Instant.now()
        cache[sessionId]?.let { if (it.expiresAt.isAfter(now)) return it.open }
        val id = runCatching { UUID.fromString(sessionId) }.getOrNull() ?: return false
        val open = sessions.findById(id).map { it.revokedAt == null }.orElse(false)
        cache[sessionId] = Cached(open, now.plus(TTL))
        return open
    }

    fun invalidate(sessionId: UUID) {
        cache.remove(sessionId.toString())
    }

    private companion object {
        val TTL: Duration = Duration.ofSeconds(30)
    }
}
