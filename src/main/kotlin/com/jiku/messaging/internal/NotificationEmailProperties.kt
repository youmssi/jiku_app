package com.jiku.messaging.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "notification.email")
data class NotificationEmailProperties(
    /** Sender address for outbound mail; set per environment via NOTIFICATION_EMAIL_FROM. */
    val from: String = "no-reply@jiku.app",
    /** Language of the emails the platform team receives; NOTIFICATION_PLATFORM_LANGUAGE. */
    val platformLanguage: String = "fr",
)
