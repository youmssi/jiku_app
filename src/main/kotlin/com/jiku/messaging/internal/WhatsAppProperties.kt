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
    val health: Health = Health(),
) {
    /** How Jikū reacts to what Meta reports about its templates and numbers (JIKU-209). */
    data class Health(
        /** How long a paused template stays unused before Jikū tries it again (Meta pauses for 3 h, then 6 h). */
        val pauseHours: Long = 3,
        /** Stop using a template Meta moved to MARKETING: it costs several times more and is capped per recipient. */
        val blockMarketingTemplates: Boolean = true,
    )

    data class Meta(
        val accessToken: String = "",
        val phoneNumberId: String = "",
        val baseUrl: String = "https://graph.facebook.com/v25.0",
        /**
         * Send each message with its own approved template (JIKU-210), named
         * `<templatePrefix><kind>` and created in French and English with
         * `POST /admin/whatsapp/templates`. Off, the single templates below are used.
         */
        val dedicatedTemplates: Boolean = true,
        val templatePrefix: String = "jiku_",
        /** Single approved template for business-initiated sends when dedicated templates are off; blank sends plain text. */
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
        /**
         * The Meta id of the Jikū number dedicated to cards (JIKU-185, ADR 106),
         * under the same app and token as the platform number. Messages to it run
         * the card conversation; blank keeps it off.
         */
        val cardsPhoneNumberId: String = "",
        /**
         * The WhatsApp Business Account of the platform number (JIKU-209): Meta
         * reports template pauses per account, so the platform sender checks its
         * templates under this id. Blank skips the check; Meta's own refusals
         * still queue the message.
         */
        val businessAccountId: String = "",
    )
}
