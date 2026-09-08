package com.jiku.catalog.internal

import com.jiku.shared.JwtService
import io.jsonwebtoken.Claims
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/**
 * Émet et vérifie le jeton signé du lien du personnel vers la console d'un
 * service (JIKU-88), même mécanique que le lien validateurs et le lien de service
 * client : le jeton porte l'identité de la ligne [ServiceStaff] (sujet), le
 * service et le tenant. Un type propre empêche qu'un lien client ou un lien
 * d'invitation soit utilisé comme lien de comptoir.
 */
@SpringService
class DayLineTokenService(
    private val jwt: JwtService,
) {
    fun issue(
        staffId: UUID,
        serviceId: UUID,
        tenantId: String,
    ): String =
        jwt.sign(
            staffId.toString(),
            mapOf(
                CLAIM_SERVICE_ID to serviceId.toString(),
                CLAIM_TENANT_ID to tenantId,
                CLAIM_TYPE to TOKEN_TYPE,
            ),
        )

    fun parse(token: String): Claims {
        val claims = jwt.parse(token)
        require(claims[CLAIM_TYPE] == TOKEN_TYPE) { "Not a day-line token" }
        return claims
    }

    companion object {
        const val CLAIM_SERVICE_ID = "serviceId"
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "dayline"
    }
}
