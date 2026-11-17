package com.jiku.tenant.internal

import com.jiku.shared.MessageLanguage
import com.jiku.shared.SecurityNotice
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/** The browser a sign-in comes from: its own random id, and what it says it is. */
data class DeviceInfo(
    val id: String?,
    val label: String?,
)

/** A signed-in session as Settings → Security lists it (JIKU-317). */
data class SessionView(
    val id: String,
    val device: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    /** The session making this request. */
    val current: Boolean,
)

/**
 * Opens, lists and closes the sessions of organizer accounts (JIKU-317), and
 * emails the holder when a sign-in comes from a browser the account has not
 * used before.
 */
@Service
class OrganizerSessionService(
    private val sessions: OrganizerSessionRepository,
    private val gate: SessionAccessGateAdapter,
    private val properties: SessionProperties,
    private val accountProperties: AccountProperties,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun open(
        user: OrganizerUser,
        device: DeviceInfo?,
    ): UUID {
        val userId = requireNotNull(user.id)
        val deviceId = device?.id?.takeIf { DEVICE_ID.matches(it) }
        val label =
            device
                ?.label
                ?.trim()
                ?.take(LABEL_LENGTH)
                ?.takeIf { it.isNotEmpty() }
        if (deviceId != null && sessions.existsByUserId(userId) && !sessions.existsByUserIdAndDeviceId(userId, deviceId)) {
            events.publishEvent(
                SecurityNotice(
                    kind = SecurityNotice.KIND_NEW_DEVICE,
                    email = user.email,
                    language = language(),
                    device = label,
                    actionUrl = securityLink(),
                ),
            )
        }
        return requireNotNull(sessions.save(OrganizerSession(userId, deviceId, label)).id)
    }

    /**
     * Records that a session renewed its tokens. False when it was closed or
     * belongs to someone else, which ends it for its holder.
     */
    @Transactional
    fun renew(
        sessionId: UUID,
        userId: UUID,
    ): Boolean {
        val session = sessions.findById(sessionId).orElse(null) ?: return false
        if (session.userId != userId || session.revokedAt != null) return false
        session.lastUsedAt = Instant.now()
        return true
    }

    @Transactional(readOnly = true)
    fun list(
        userId: UUID,
        currentId: String?,
    ): List<SessionView> =
        sessions
            .findByUserIdAndRevokedAtIsNullAndLastUsedAtAfterOrderByLastUsedAtDesc(userId, Instant.now().minus(properties.idleListing))
            .map {
                val id = requireNotNull(it.id).toString()
                SessionView(id = id, device = it.device, createdAt = it.createdAt, lastUsedAt = it.lastUsedAt, current = id == currentId)
            }

    @Transactional
    fun close(
        userId: UUID,
        sessionId: UUID,
    ) {
        val session =
            sessions.findById(sessionId).orElse(null)?.takeIf { it.userId == userId }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found")
        revoke(session)
    }

    /** Closes every session of [userId] except [keep] ("sign out everywhere else"). */
    @Transactional
    fun closeAll(
        userId: UUID,
        keep: UUID? = null,
    ) {
        sessions.findByUserIdAndRevokedAtIsNull(userId).filter { it.id != keep }.forEach(::revoke)
    }

    private fun revoke(session: OrganizerSession) {
        if (session.revokedAt == null) session.revokedAt = Instant.now()
        gate.invalidate(requireNotNull(session.id))
    }

    private fun language(): String = MessageLanguage.normalize(LocaleContextHolder.getLocale().toLanguageTag())

    private fun securityLink(): String {
        val language = language()
        val prefix = if (language == MessageLanguage.FRENCH) "" else "/$language"
        return "${accountProperties.appBaseUrl}$prefix/settings?tab=security"
    }

    private companion object {
        val DEVICE_ID = Regex("^[A-Za-z0-9_-]{16,64}$")
        const val LABEL_LENGTH = 200
    }
}
