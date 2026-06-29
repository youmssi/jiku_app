package com.jiku.notification.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "notification.send")
data class NotificationSendProperties(
    /** Delivery attempts before a notification is recorded as permanently failed. */
    val maxAttempts: Int = 3,
)
