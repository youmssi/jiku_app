package com.jiku.messaging.internal

/**
 * Every routed email provider's daily quota (JIKU-62) is exhausted for today.
 * Deliberately distinct from [EmailDeliveryException]: this is not a delivery
 * failure, it means "not yet — try again tomorrow once quotas reset", so
 * callers must queue rather than mark the send permanently failed.
 */
class EmailQuotaExceededException(
    message: String,
) : RuntimeException(message)
