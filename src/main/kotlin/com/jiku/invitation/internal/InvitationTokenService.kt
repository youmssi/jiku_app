package com.jiku.invitation.internal

import com.jiku.shared.JwtService
import io.jsonwebtoken.Claims
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Issues and verifies the signed, stateless token embedded in a guest's
 * invitation link. The token carries the guest and event ids and is verified by
 * the guest-facing RSVP flow (JIKU-19); nothing is stored server-side.
 */
@Service
class InvitationTokenService(
    private val jwt: JwtService,
) {
    fun issue(
        guestId: UUID,
        eventId: UUID,
        tenantId: String,
    ): String =
        jwt.sign(
            guestId.toString(),
            mapOf(
                CLAIM_EVENT_ID to eventId.toString(),
                CLAIM_TENANT_ID to tenantId,
                CLAIM_TYPE to TOKEN_TYPE,
            ),
        )

    fun parse(token: String): Claims = jwt.parse(token)

    companion object {
        const val CLAIM_EVENT_ID = "eventId"
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "invitation"
    }
}
