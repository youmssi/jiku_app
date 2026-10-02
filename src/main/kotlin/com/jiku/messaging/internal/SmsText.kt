package com.jiku.messaging.internal

import java.text.Normalizer

/**
 * Keeps SMS bodies in the GSM-7 alphabet (JIKU-206). One character outside it,
 * such as the "ū" of Jikū, a curly apostrophe or an emoji, switches the whole
 * message to UCS-2, where a segment holds 70 characters instead of 160: the same
 * text then costs two or three SMS instead of one. Accented letters that GSM-7
 * lacks lose their accent, typographic punctuation becomes its plain form, and
 * anything else that has no GSM-7 equivalent is dropped.
 */
object SmsText {
    private const val BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val EXTENSION = "^{}\\[~]|€"
    private const val SINGLE_SEGMENT = 160
    private const val MULTI_SEGMENT = 153

    private val replacements =
        mapOf(
            '’' to "'",
            '‘' to "'",
            'ʼ' to "'",
            '´' to "'",
            '`' to "'",
            '“' to "\"",
            '”' to "\"",
            '–' to "-",
            '—' to "-",
            '−' to "-",
            '…' to "...",
            '•' to "-",
            '·' to "-",
            '\u00A0' to " ",
            '\u202F' to " ",
            '\u2009' to " ",
            '\t' to " ",
            'œ' to "oe",
            'Œ' to "OE",
        )

    private val frenchQuotes = Regex("«[\\s\u00A0\u202F]*|[\\s\u00A0\u202F]*»")

    private fun isGsm(c: Char) = c in BASIC || c in EXTENSION

    fun toGsm7(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text.replace(frenchQuotes, "\"")) {
            when {
                isGsm(c) -> out.append(c)
                c in replacements -> out.append(replacements.getValue(c))
                else -> {
                    val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull()
                    if (base != null && base != c && isGsm(base)) out.append(base)
                }
            }
        }
        return out.toString().replace(Regex(" {2,}"), " ").trim()
    }

    /** How many SMS a GSM-7 body costs; extension characters take two places. */
    fun segments(gsmText: String): Int {
        val length = gsmText.sumOf { if (it in EXTENSION) 2 else 1 }
        return when {
            length == 0 -> 0
            length <= SINGLE_SEGMENT -> 1
            else -> (length + MULTI_SEGMENT - 1) / MULTI_SEGMENT
        }
    }
}
