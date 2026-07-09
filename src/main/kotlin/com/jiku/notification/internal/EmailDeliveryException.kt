package com.jiku.notification.internal

/**
 * A provider adapter failed to deliver an email. Thrown by [EmailSender]
 * implementations so the orchestration layer's bounded retry (JIKU-16) engages
 * uniformly, whatever the underlying transport.
 */
class EmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
