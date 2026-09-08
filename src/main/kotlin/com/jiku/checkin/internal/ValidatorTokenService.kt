package com.jiku.checkin.internal

import com.jiku.shared.JwtService
import io.jsonwebtoken.Claims
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Issues and verifies the signed token embedded in a validator access link. The
 * token carries the validator and event ids; it is verified statelessly, while the
 * validator row (looked up by the token's subject) governs revocation. A separate
 * token type prevents an invitation or access token from being used as a check-in
 * link.
 */
@Service
class ValidatorTokenService(
    private val jwt: JwtService,
    private val properties: ValidatorLinkProperties,
) {
    fun issue(
        validatorId: UUID,
        eventId: UUID,
        tenantId: String,
    ): String =
        jwt.sign(
            validatorId.toString(),
            mapOf(
                CLAIM_EVENT_ID to eventId.toString(),
                CLAIM_TENANT_ID to tenantId,
                CLAIM_TYPE to TOKEN_TYPE,
            ),
            properties.validity,
        )

    fun parse(token: String): Claims {
        val claims = jwt.parse(token)
        require(claims[CLAIM_TYPE] == TOKEN_TYPE) { "Not a validator token" }
        return claims
    }

    companion object {
        const val CLAIM_EVENT_ID = "eventId"
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "validator"
    }
}
