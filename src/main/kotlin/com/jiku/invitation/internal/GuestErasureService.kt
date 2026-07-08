package com.jiku.invitation.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Irreversibly anonymizes a guest's personal identifiers (JIKU-36) while leaving
 * the row — and therefore the organizer's aggregate counts and any preserved
 * check-in record — intact. This is the single anonymization implementation; the
 * self-service erasure endpoint and the retention job (JIKU-37) both go through it
 * rather than duplicating the logic.
 *
 * Idempotent: erasing an already-erased guest changes nothing but still records the
 * request for the audit trail. Tenant must be bound by the caller.
 */
@Service
class GuestErasureService(
    private val guests: GuestRepository,
    private val erasureLogs: GuestErasureLogRepository,
) {
    @Transactional
    fun eraseGuest(
        guestId: UUID,
        reason: ErasureReason,
    ): Boolean {
        val guest = guests.findById(guestId).orElse(null) ?: return false
        if (!guest.personalDataErased) {
            guest.firstName = ERASED_FIRST_NAME
            guest.lastName = ERASED_LAST_NAME
            guest.email = null
            guest.phoneNumber = null
            guest.personalDataErased = true
            guest.erasedAt = Instant.now()
            guests.save(guest)
        }
        erasureLogs.save(GuestErasureLog(guestId = guestId, eventId = guest.eventId, reason = reason))
        return true
    }

    companion object {
        const val ERASED_FIRST_NAME = "Deleted"
        const val ERASED_LAST_NAME = "Guest"
    }
}
