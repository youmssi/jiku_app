package com.jiku.catalog.internal

import com.jiku.shared.JwtService
import io.jsonwebtoken.Claims
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/**
 * Operator links (JIKU-116). [appBaseUrl] is the frontend origin the shareable
 * links point to; [validity] is how long a signed link stays usable before it is
 * issued again from the operator's short code. Revocation is immediate and
 * independent of [validity].
 */
@ConfigurationProperties(prefix = "catalog.operator-link")
data class OperatorLinkProperties(
    val appBaseUrl: String = "http://localhost:3000",
    val validity: Duration = Duration.ofDays(30),
)

/** A verified link: who it belongs to, and the event or service it is pinned to, if any. */
data class OperatorClaims(
    val operatorId: UUID,
    val tenantId: String,
    val pinnedEventId: UUID?,
    val pinnedServiceId: UUID?,
)

/**
 * Issues and verifies the signed token of an operator link. The token carries the
 * operator (subject) and tenant, and may pin one event or service so a link
 * handed out for a single door or counter opens straight on it.
 *
 * Tokens of the door links (JIKU-23) and counter links (JIKU-88) that operators
 * replaced are still accepted: their rows became operators with the same ids, and
 * they pin their event or service under the same claims.
 */
@SpringService
class OperatorTokenService(
    private val jwt: JwtService,
    private val properties: OperatorLinkProperties,
) {
    fun issue(
        operatorId: UUID,
        tenantId: String,
        pinnedEventId: UUID? = null,
        pinnedServiceId: UUID? = null,
    ): String =
        jwt.sign(
            operatorId.toString(),
            mapOf(
                CLAIM_TENANT_ID to tenantId,
                CLAIM_EVENT_ID to pinnedEventId?.toString(),
                CLAIM_SERVICE_ID to pinnedServiceId?.toString(),
                CLAIM_TYPE to TOKEN_TYPE,
            ),
            properties.validity,
        )

    /** Verifies [token], or returns null when it is not a valid operator link. */
    fun parse(token: String): OperatorClaims? {
        val claims = runCatching { jwt.parse(token) }.getOrNull() ?: return null
        if (claims[CLAIM_TYPE] !in ACCEPTED_TYPES) return null
        return runCatching {
            OperatorClaims(
                operatorId = UUID.fromString(claims.subject),
                tenantId = claims[CLAIM_TENANT_ID] as String,
                pinnedEventId = claims.uuid(CLAIM_EVENT_ID),
                pinnedServiceId = claims.uuid(CLAIM_SERVICE_ID),
            )
        }.getOrNull()
    }

    private fun Claims.uuid(name: String): UUID? = (this[name] as? String)?.let(UUID::fromString)

    private companion object {
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_EVENT_ID = "eventId"
        const val CLAIM_SERVICE_ID = "serviceId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "operator"
        val ACCEPTED_TYPES = setOf(TOKEN_TYPE, "validator", "dayline")
    }
}
