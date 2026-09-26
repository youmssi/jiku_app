package com.jiku.messaging.internal

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
    /**
     * Sends are queued (JIKU-61) once a pool's 24h rolling conversation count
     * reaches this many — a safety margin under Meta's hard tier ceiling (250 for
     * an unverified/Tier-1 business), so a burst never actually hits the wall and
     * risks Meta throttling or reclassifying the account.
     */
    val conversationSafetyThreshold: Int = 220,
    /** USD-to-GNF rate used to record WhatsApp message cost in both currencies. */
    val usdToGnfRate: Long = 8760,
    /**
     * Comma-separated, case-insensitive substrings that mark a rendered body as
     * promotional (MARKETING) rather than transactional (UTILITY) content — French
     * by default since the shipped templates are French. Configurable, not
     * hardcoded, because what counts as promotional is a judgment call that may
     * need tuning without a redeploy.
     */
    val marketingMarkers: String =
        "promo,réduction,solde,gratuit,offre spéciale,cadeau,bon plan,code promo,achetez maintenant",
) {
    data class Meta(
        val accessToken: String = "",
        val phoneNumberId: String = "",
        val baseUrl: String = "https://graph.facebook.com/v21.0",
        /** Approved template for business-initiated sends; blank sends plain text. */
        val templateName: String = "",
        val templateLanguage: String = "fr",
        /**
         * Approved template with two quick-reply buttons (accept, decline) for
         * interactive invitations (JIKU-143); blank sends session reply buttons.
         */
        val buttonsTemplateName: String = "",
        /** Approved template with an image header, for the ticket and its QR code (JIKU-143); blank sends a session image. */
        val imageTemplateName: String = "",
        /** The Meta app secret that signs inbound webhook calls (JIKU-143); blank refuses every call. */
        val appSecret: String = "",
        /** The token Meta echoes when the webhook is registered (JIKU-143); blank refuses registration. */
        val verifyToken: String = "",
        /**
         * The Meta app organizations connect their own number to through
         * Embedded Signup (ADR 105), with the signup configuration made for it
         * in Meta. Blank keeps Embedded Signup off.
         */
        val appId: String = "",
        val embeddedSignupConfigId: String = "",
    )
}
