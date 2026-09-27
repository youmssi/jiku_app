package com.jiku.messaging.internal

import com.jiku.shared.MessageLanguage
import com.jiku.shared.TenantLanguage
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * The copy of every outbound message, per language: subjects, preheaders, labels
 * and dates from `email-templates/{lang}/messages.properties`, email partials
 * framed by the shared `layout.html`, and the WhatsApp texts. Resources are read
 * once and cached; `{{variable}}` substitution takes values the caller has
 * already escaped for its channel.
 */
@Component
class MessageCatalog(
    private val tenantLanguage: TenantLanguage,
) {
    private val messages = ConcurrentHashMap<String, Properties>()
    private val resources = ConcurrentHashMap<String, String>()

    /** The organization's language, French when there is no organization to ask. */
    fun language(tenantId: String?): String = tenantId?.let { tenantLanguage.of(it) } ?: MessageLanguage.FRENCH

    fun text(
        language: String,
        key: String,
        values: Map<String, String> = emptyMap(),
    ): String = textOrNull(language, key, values) ?: error("Missing message '$key' for language '$language'")

    fun textOrNull(
        language: String,
        key: String,
        values: Map<String, String> = emptyMap(),
    ): String? = messages(language).getProperty(key)?.let { substitute(it, values) }

    /** [instant] in [zone], written with the pattern stored under [patternKey]. */
    fun formatDate(
        language: String,
        patternKey: String,
        instant: Instant,
        zone: ZoneId,
    ): String =
        DateTimeFormatter
            .ofPattern(text(language, patternKey), MessageLanguage.locale(language))
            .format(instant.atZone(zone))

    /**
     * [instant] in [zone], short enough to read at a glance (JIKU-188): "sam. 14
     * nov. · 19 h", the year only when it is not obvious, the minutes only when
     * they are not zero. Without [weekday], "7 nov. · 20 h".
     */
    fun shortWhen(
        language: String,
        instant: Instant,
        zone: ZoneId,
        weekday: Boolean = true,
        now: Instant = Instant.now(),
    ): String {
        val at = instant.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val withYear =
            ChronoUnit.DAYS.between(today, at.toLocalDate()) > YEAR_SHOWN_AFTER_DAYS || at.year < today.year
        val dayKey = (if (weekday) "date.short.weekday" else "date.short.day") + (if (withYear) "Year" else "")
        val timeKey = if (at.minute == 0) "date.short.hour" else "date.short.hourMinutes"
        val locale = MessageLanguage.locale(language)
        val day = DateTimeFormatter.ofPattern(text(language, dayKey), locale).format(at)
        val time = DateTimeFormatter.ofPattern(text(language, timeKey), locale).format(at)
        return "$day · $time"
    }

    /**
     * The full email document for [name]: the layout around the language's
     * partial, with the layout-only parts (language, preheader, footer, and the
     * [brand] shown above the card, already HTML) filled in. The message variables
     * stay as `{{…}}` for the caller — or a tenant's editor — to substitute.
     */
    fun emailDocument(
        language: String,
        name: String,
        footerKey: String,
        brand: String,
    ): String {
        val lang = MessageLanguage.normalize(language)
        return resource("email-templates/layout.html")
            .replace("{{brandName}}", brand)
            .replace("{{lang}}", lang)
            .replace("{{preheader}}", textOrNull(lang, "$name.preheader").orEmpty())
            .replace("{{footer}}", text(lang, footerKey))
            .replace("{{content}}", resource("email-templates/$lang/$name.html").trimEnd())
    }

    fun whatsAppTemplate(
        language: String,
        file: String,
    ): String = resource("whatsapp-templates/${MessageLanguage.normalize(language)}/$file").trimEnd()

    fun substitute(
        body: String,
        values: Map<String, String>,
    ): String = values.entries.fold(body) { acc, (key, value) -> acc.replace("{{$key}}", value) }

    private fun messages(language: String): Properties =
        messages.computeIfAbsent(MessageLanguage.normalize(language)) { lang ->
            Properties().apply {
                ClassPathResource("email-templates/$lang/messages.properties").inputStream.reader(Charsets.UTF_8).use { load(it) }
            }
        }

    private fun resource(path: String): String =
        resources.computeIfAbsent(path) {
            ClassPathResource(it).inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }
}

/** Beyond this many days ahead, a short date writes out its year. */
private const val YEAR_SHOWN_AFTER_DAYS = 330L
