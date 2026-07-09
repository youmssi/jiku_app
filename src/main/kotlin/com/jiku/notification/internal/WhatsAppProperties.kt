package com.jiku.notification.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level WhatsApp delivery configuration. `transport` selects the
 * adapter (`log` records sends without delivering; `meta` uses the WhatsApp
 * Cloud API with the credentials below). Tenants may override the platform
 * credentials with their own in the org settings (JIKU-44).
 */
@ConfigurationProperties(prefix = "jiku.whatsapp")
data class WhatsAppProperties(
    val transport: String = "log",
    val meta: Meta = Meta(),
) {
    data class Meta(
        val accessToken: String = "",
        val phoneNumberId: String = "",
        val baseUrl: String = "https://graph.facebook.com/v21.0",
        /** Approved template for business-initiated sends; blank sends plain text. */
        val templateName: String = "",
        val templateLanguage: String = "fr",
    )
}
