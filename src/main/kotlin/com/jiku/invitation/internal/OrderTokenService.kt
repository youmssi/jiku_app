package com.jiku.invitation.internal

import com.jiku.shared.JwtService
import io.jsonwebtoken.Claims
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The signed link that brings a buyer back to its ticket order (JIKU-177): it
 * carries the order and its tenant, like an invitation link carries a guest.
 * Nothing is stored server-side, and the link does not expire: the buyer uses
 * it to declare its payment, then to open its tickets.
 */
@Service
class OrderTokenService(
    private val jwt: JwtService,
) {
    fun issue(
        orderId: UUID,
        tenantId: String,
    ): String = jwt.sign(orderId.toString(), mapOf(CLAIM_TENANT_ID to tenantId, CLAIM_TYPE to TOKEN_TYPE))

    fun parse(token: String): Claims = jwt.parse(token)

    companion object {
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "ticket-order"
    }
}
