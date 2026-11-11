package com.jiku.messaging.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "notification.email")
data class NotificationEmailProperties(
    /** Sender address for outbound mail; set per environment via NOTIFICATION_EMAIL_FROM. */
    val from: String = "no-reply@hellojiku.com",
    /** Language of the emails the platform team receives; NOTIFICATION_PLATFORM_LANGUAGE. */
    val platformLanguage: String = "fr",
    /** The web app's public address, for the links emails carry (JIKU-278); SERVER_BASE_URL. */
    val siteUrl: String = "http://localhost:3000",
    /** Where the emails' brand images are served, the web's `/email` folder; EMAIL_ASSET_BASE_URL. */
    val assetBaseUrl: String = "http://localhost:3000/email",
)
