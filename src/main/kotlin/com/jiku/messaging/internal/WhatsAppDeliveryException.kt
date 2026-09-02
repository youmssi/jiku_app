package com.jiku.messaging.internal

/**
 * A provider adapter failed to deliver a WhatsApp message. Thrown by
 * [WhatsAppSender] implementations so the orchestration layer's bounded retry
 * engages uniformly, whatever the underlying provider.
 */
class WhatsAppDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
