package com.jiku.messaging.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "notification.send")
data class NotificationSendProperties(
    /** Delivery attempts before a notification is recorded as permanently failed. */
    val maxAttempts: Int = 3,
    /**
     * Wait before each new attempt, in milliseconds (JIKU-215): a provider
     * that drops for a few seconds no longer turns into a failure. The last
     * value repeats when there are more attempts than values. Messages that
     * fall back to SMS (reminders, "your turn") retry at once instead.
     */
    val retryBackoffMs: List<Long> = listOf(2_000, 8_000),
) {
    fun backoffBefore(attempt: Int): Long = retryBackoffMs.getOrNull(attempt - 2) ?: retryBackoffMs.lastOrNull() ?: 0
}
