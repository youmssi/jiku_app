package com.jiku.notification.internal

/**
 * A rendered WhatsApp body was classified MARKETING (JIKU-61) but no admin
 * override is active. Sending it as UTILITY risks Meta reclassifying the
 * conversation and billing at the (5-8x higher) marketing rate, or the
 * account being flagged for category misuse — this must never be retried
 * automatically, only resolved by a human either fixing the content or
 * explicitly enabling the override.
 */
class WhatsAppContentPolicyException(
    message: String,
) : RuntimeException(message)
