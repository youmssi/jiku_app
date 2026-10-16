package com.jiku.invitation.internal

import com.jiku.shared.RandomCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * The short code of a guest's personal link (JIKU-248): `/v/K7M2QX9PAB` rather
 * than a 400-character signed token, which reads as spam in WhatsApp and costs
 * several SMS segments. A code is drawn once per guest and kept, so every
 * message to that guest carries the same link. Signed tokens already sent keep
 * resolving: the RSVP endpoints accept both.
 */
@Service
class GuestInviteCodes(
    private val guests: GuestRepository,
    private val tokens: InvitationTokenService,
) {
    /**
     * What goes after `/v/` or `/rsvp/` for this guest: their code, drawn and
     * saved on first use. Inside a read-only transaction nothing can be saved,
     * so a guest without a code yet gets a signed token, which resolves the
     * same way. Runs under the guest's tenant.
     */
    fun linkKey(
        guest: Guest,
        tenantId: String,
    ): String {
        guest.inviteCode?.let { return it }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return tokens.issue(requireNotNull(guest.id), requireNotNull(guest.eventId), tenantId)
        }
        return codeFor(guest)
    }

    /**
     * The guest's code, drawn and saved on first use. Only the code column is
     * written and [guest] is left untouched, so a stale copy of the guest is
     * never flushed over a newer answer or transfer; a code set meanwhile by
     * another sender wins.
     */
    @Transactional
    fun codeFor(guest: Guest): String {
        guest.inviteCode?.let { return it }
        val id = requireNotNull(guest.id)
        repeat(ATTEMPTS) {
            val candidate = RandomCode.generate(LENGTH)
            if (!guests.inviteCodeTaken(candidate)) {
                return if (guests.assignInviteCode(id, candidate) == 1) candidate else requireNotNull(guests.inviteCodeOf(id))
            }
        }
        throw IllegalStateException("Could not draw a free invitation code")
    }

    /** Where [code] leads, or null when it is not a short code or matches no guest. */
    fun route(code: String): GuestInviteRoute? {
        val normalized = code.trim().uppercase()
        if (!SHAPE.matches(normalized)) return null
        return guests.routeInviteCode(normalized)
    }

    companion object {
        /** 31 symbols, 10 characters: about 49 bits, out of reach of guessing behind the public rate limits. */
        const val LENGTH = 10
        private const val ATTEMPTS = 5
        private val SHAPE = Regex("^[A-HJKMNP-Z2-9]{$LENGTH}$")

        fun looksLikeCode(value: String): Boolean = SHAPE.matches(value.trim().uppercase())
    }
}
