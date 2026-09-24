package com.jiku.shared

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The languages messages are written in. French leads: the launch markets read
 * French first, and it is what an organization of a French-speaking country
 * gets until it chooses otherwise.
 */
object MessageLanguage {
    const val FRENCH = "fr"
    const val ENGLISH = "en"

    val SUPPORTED: Set<String> = setOf(FRENCH, ENGLISH)

    /** ISO 3166-1 alpha-2 countries where French is an official or working language of business. */
    private val FRENCH_SPEAKING =
        setOf(
            "BE", "BF", "BI", "BJ", "CA", "CD", "CF", "CG", "CH", "CI", "CM", "DJ", "DZ", "FR", "GA", "GN", "GQ",
            "HT", "KM", "LU", "MA", "MC", "MG", "ML", "MR", "NE", "RW", "SC", "SN", "TD", "TG", "TN", "VU",
        )

    fun forCountry(country: String?): String = if (country?.uppercase() in FRENCH_SPEAKING) FRENCH else ENGLISH

    /** A supported language from any tag ("fr-GN", "en", "FR"); French when the tag is unknown. */
    fun normalize(tag: String?): String {
        val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
        return if (language in SUPPORTED) language!! else FRENCH
    }

    fun locale(language: String): Locale = Locale.forLanguageTag(normalize(language))

    /**
     * An event's start as a guest reads it, in the event's own timezone:
     * "mardi 3 novembre 2026 à 15:00" or "Tuesday 3 November 2026 at 15:00".
     */
    fun formatEventStart(
        instant: Instant,
        timezone: String,
        language: String,
    ): String {
        val pattern = if (normalize(language) == FRENCH) "EEEE d MMMM uuuu 'à' HH:mm" else "EEEE d MMMM uuuu 'at' HH:mm"
        return DateTimeFormatter
            .ofPattern(pattern, locale(language))
            .withZone(ZoneId.of(timezone))
            .format(instant)
            .replaceFirstChar { it.uppercase() }
    }
}

/**
 * The language an organization's messages go out in, exposed in `shared` for the
 * same reason as [TenantCurrency]: messaging cannot depend on the tenant module.
 * The tenant module provides the implementation.
 */
fun interface TenantLanguage {
    /** One of [MessageLanguage.SUPPORTED]. */
    fun of(tenantId: String): String
}
