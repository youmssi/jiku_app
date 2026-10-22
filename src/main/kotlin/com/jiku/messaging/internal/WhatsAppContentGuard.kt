package com.jiku.messaging.internal

import org.springframework.stereotype.Component

/**
 * Blocks a WhatsApp send whose rendered body looks promotional from going out
 * as (cheap) UTILITY content (JIKU-61) — Meta reclassifies such conversations
 * as MARKETING server-side and bills 5-8x more, or flags the account for
 * category misuse. An administrator can explicitly override this (logged via
 * [WhatsAppContentOverrideRepository] / the admin audit log), acknowledging the
 * higher expected cost, rather than the app silently sending anyway.
 *
 * Deliberately not `@Transactional` itself — see [WhatsAppConversationCounter]'s
 * KDoc for why a guard that throws must not carry its own transactional advice.
 */
@Component
class WhatsAppContentGuard(
    private val properties: WhatsAppProperties,
    private val overrides: WhatsAppContentOverrideRepository,
) {
    /** [WhatsAppPricing.CATEGORY_MARKETING] or [WhatsAppPricing.CATEGORY_UTILITY]. */
    fun classify(body: String): String =
        if (promotionalWords(body).isEmpty()) WhatsAppPricing.CATEGORY_UTILITY else WhatsAppPricing.CATEGORY_MARKETING

    /**
     * The configured promotional words [body] contains, the very ones [classify]
     * blocks on, so an organizer checking a message while writing it (JIKU-269)
     * gets the verdict the send will get.
     */
    fun promotionalWords(body: String): List<String> {
        val normalized = body.lowercase().replace(WHITESPACE, " ")
        return properties.marketingMarkers
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && wholeWords(it).containsMatchIn(normalized) }
            .distinct()
    }

    /** [marker] as whole words, so "promo" does not flag "promotionnel". */
    private fun wholeWords(marker: String): Regex {
        val start = if (marker.first().isLetterOrDigit()) "(?<![\\p{L}\\p{N}])" else ""
        val end = if (marker.last().isLetterOrDigit()) "(?![\\p{L}\\p{N}])" else ""
        return Regex(start + Regex.escape(marker.replace(WHITESPACE, " ")) + end)
    }

    fun assertAllowed(category: String) {
        if (category != WhatsAppPricing.CATEGORY_MARKETING) {
            return
        }
        val active = overrides.findFirstByOrderByUpdatedAtDesc()?.active ?: false
        if (!active) {
            throw WhatsAppContentPolicyException(
                "Rendered WhatsApp body contains promotional language but is being sent as UTILITY content; " +
                    "enable the admin content override to send it anyway",
            )
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
