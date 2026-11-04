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

    /** Guests only (JIKU-293): an organizer's own messages stay in French or English. */
    const val ARABIC = "ar"

    /** The languages an organization and its team read. */
    val SUPPORTED: Set<String> = setOf(FRENCH, ENGLISH)

    /** The languages a guest can be written to in, chosen per event. */
    val GUEST: Set<String> = setOf(FRENCH, ENGLISH, ARABIC)

    /** ISO 3166-1 alpha-2 countries where French is an official or working language of business. */
    private val FRENCH_SPEAKING: Set<String> =
        (
            "BE BF BI BJ CA CD CF CG CH CI CM DJ DZ FR GA GN GQ " +
                "HT KM LU MA MC MG ML MR NE RW SC SN TD TG TN VU"
        ).split(' ').toSet()

    fun forCountry(country: String?): String = if (country?.uppercase() in FRENCH_SPEAKING) FRENCH else ENGLISH

    /** A supported language from any tag ("fr-GN", "en", "FR"); French when the tag is unknown. */
    fun normalize(tag: String?): String {
        val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
        return if (language in SUPPORTED) language!! else FRENCH
    }

    /** A guest language from any tag; French when the tag is unknown. */
    fun normalizeGuest(tag: String?): String {
        val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
        return if (language in GUEST) language!! else FRENCH
    }

    /**
     * The language an event's guests read: the one chosen on the event, else the
     * organization's ([forCountry]).
     */
    fun forGuests(
        eventLanguage: String?,
        country: String?,
    ): String = eventLanguage?.takeIf { it in GUEST } ?: forCountry(country)

    /** The path prefix of a guest page in [language]: none for French, the site's default. */
    fun pathPrefix(language: String): String = normalizeGuest(language).let { if (it == FRENCH) "" else "/$it" }

    /** Arabic keeps Western digits, as on the guest pages and in phone numbers and codes. */
    fun locale(language: String): Locale =
        when (val lang = normalizeGuest(language)) {
            ARABIC -> Locale.forLanguageTag("ar-u-nu-latn")
            else -> Locale.forLanguageTag(lang)
        }

    /**
     * An event's start as a guest reads it, in the event's own timezone:
     * "mardi 3 novembre 2026 à 15:00", "Tuesday 3 November 2026 at 15:00" or
     * "الثلاثاء 3 نوفمبر 2026 الساعة 15:00".
     */
    fun formatEventStart(
        instant: Instant,
        timezone: String,
        language: String,
    ): String {
        val pattern =
            when (normalizeGuest(language)) {
                FRENCH -> "EEEE d MMMM uuuu 'à' HH:mm"
                ARABIC -> "EEEE d MMMM uuuu 'الساعة' HH:mm"
                else -> "EEEE d MMMM uuuu 'at' HH:mm"
            }
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
