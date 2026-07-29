package com.jiku.notification.internal

/**
 * The 24h rolling conversation-window safety threshold (JIKU-61) has been
 * reached for the relevant pool (platform or a tenant's own credentials).
 * Deliberately distinct from [WhatsAppDeliveryException]: this is not a
 * delivery failure, it means "not yet — try again once capacity frees up",
 * so callers must queue rather than mark the send permanently failed.
 */
class WhatsAppQuotaExceededException(
    message: String,
) : RuntimeException(message)
