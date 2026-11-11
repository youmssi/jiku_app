package com.jiku.messaging.internal

/**
 * Derives the plain-text part of an email from its HTML (JIKU-279), so every
 * message goes out as `multipart/alternative`: spam filters score an HTML-only
 * email lower, and text-only clients and screen readers get a clean version.
 *
 * Built for the documents [EmailTemplateRenderer] produces: the head, styles,
 * hidden preheader and images are dropped, a link keeps its address in
 * parentheses, and block ends become line breaks (single ones inside a table
 * cell, so a label stays above its value).
 */
object PlainText {
    fun fromHtml(html: String): String {
        var text = html
        text = DROPPED_BLOCKS.replace(text, "")
        text = HIDDEN.replace(text, "")
        text = WHITESPACE.replace(text, " ")
        text = LINK.replace(text) { match -> link(match.groupValues[1], match.groupValues[2]) }
        text = LINE_BREAK.replace(text, "\n")
        text = LEAF_CELL.replace(text) { match -> tightCell(match.groupValues[1], match.groupValues[2]) }
        text = BLOCK_END.replace(text, "\n\n")
        text = CELL_END.replace(text, " ")
        text = TAG.replace(text, "")
        text = decodeEntities(text)
        return text
            .lines()
            .joinToString("\n") { SPACES.replace(it, " ").trim() }
            .replace(BLANK_LINES, "\n\n")
            .trim()
    }

    private fun link(
        href: String,
        inner: String,
    ): String {
        val url = decodeEntities(href).trim()
        val label = SPACES.replace(decodeEntities(TAG.replace(inner, " ")), " ").trim()
        return when {
            label.isEmpty() || label == url -> url
            url.isEmpty() || url.startsWith("#") -> label
            else -> "$label ($url)"
        }
    }

    /**
     * A cell holding only text keeps its lines together, so a label sits right above
     * its value. The layout's own padded cells (`px`) hold the message body and keep
     * their blank lines between paragraphs.
     */
    private fun tightCell(
        open: String,
        inner: String,
    ): String {
        if (LAYOUT_CELL.containsMatchIn(open) || !BLOCK_END.containsMatchIn(inner)) return "$open$inner</td>"
        val lines = BLOCK_END.replace(inner, "\n").trimEnd() + "\n"
        return "$open$lines</td>"
    }

    private fun decodeEntities(text: String): String =
        ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x", ignoreCase = true) -> codePoint(name.substring(2).toIntOrNull(16))
                name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull())
                else -> NAMED[name.lowercase()] ?: match.value
            }
        }

    /** Invisible padding characters (hair spaces, joiners) used in the preheader become nothing. */
    private fun codePoint(value: Int?): String =
        when {
            value == null || !Character.isValidCodePoint(value) -> ""
            value in INVISIBLE -> ""
            value == NBSP -> " "
            else -> String(Character.toChars(value))
        }

    private const val NBSP = 0xA0
    private val INVISIBLE = setOf(0x200A, 0x34F, 0x200B, 0x200C, 0x200D, 0xFEFF, 0xAD)
    private val NAMED =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "rsquo" to "\u2019",
            "lsquo" to "\u2018",
            "ldquo" to "\u201C",
            "rdquo" to "\u201D",
            "laquo" to "\u00AB",
            "raquo" to "\u00BB",
            "hellip" to "\u2026",
            "mdash" to "\u2014",
            "ndash" to "\u2013",
            "middot" to "\u00B7",
            "euro" to "\u20AC",
        )

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val DROPPED_BLOCKS = Regex("<(head|style|script|title)\\b.*?</\\1\\s*>", OPTIONS)
    private val HIDDEN = Regex("<div\\b[^>]*display\\s*:\\s*none[^>]*>.*?</div\\s*>", OPTIONS)
    private val LINK = Regex("<a\\b[^>]*?href\\s*=\\s*\"([^\"]*)\"[^>]*>(.*?)</a\\s*>", OPTIONS)
    private val LINE_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("</(p|div|h[1-6]|tr|table|li|ul|ol)\\s*>", RegexOption.IGNORE_CASE)
    private val LEAF_CELL = Regex("(<td\\b[^>]*>)((?:(?!<t[dhr]\\b|<table\\b).)*?)</td\\s*>", OPTIONS)
    private val LAYOUT_CELL = Regex("class\\s*=\\s*\"[^\"]*\\bpx\\b", RegexOption.IGNORE_CASE)
    private val CELL_END = Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>", OPTIONS)
    private val ENTITY = Regex("&(#x[0-9a-f]+|#[0-9]+|[a-z]+);", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val BLANK_LINES = Regex("\\n{3,}")
}
