package com.jiku.catalog.internal

import com.jiku.shared.JwtService
import io.jsonwebtoken.Claims
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/**
 * Émet et vérifie le lien de service signé (JIKU-87) : l'organisateur le partage,
 * le client s'en sert sans compte pour voir le service et ses créneaux puis
 * réserver. Le jeton porte le tenant et le service ; rien n'est stocké côté
 * serveur, il est vérifié par le flux public sous /appointments/{token}.
 */
@SpringService
class ServiceLinkTokenService(
    private val jwt: JwtService,
) {
    fun issue(
        serviceId: UUID,
        tenantId: String,
    ): String =
        jwt.sign(
            serviceId.toString(),
            mapOf(
                CLAIM_TENANT_ID to tenantId,
                CLAIM_TYPE to TOKEN_TYPE,
            ),
        )

    fun parse(token: String): Claims = jwt.parse(token)

    companion object {
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "service-link"
    }
}
