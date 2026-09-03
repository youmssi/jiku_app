package com.jiku.shared.observability

/**
 * Redacts personal data from text before it leaves the process for a third-party
 * error tracker.
 *
 * Guest emails and phone numbers routinely end up inside exception messages — a
 * failed provider send quoting the recipient, a constraint violation quoting the
 * offending value, a parse failure quoting the input row. Forwarding those to an
 * external processor would silently undo the erasure and retention guarantees the
 * product makes to guests, who never agreed to appear in a third-party system and
 * cannot be reached to consent.
 *
 * The patterns deliberately over-redact: a bare numeric string that happens to look
 * like a phone number is redacted whether or not it is one. Losing a little
 * debuggability is an acceptable cost; leaking a guest's contact details is not.
 */
class PersonalDataScrubber {
    /** Returns [text] with any email address or phone-shaped run of digits replaced. */
    fun scrub(text: String): String =
        text
            .replace(EMAIL, EMAIL_PLACEHOLDER)
            .replace(PHONE, PHONE_PLACEHOLDER)

    private companion object {
        val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

        /**
         * A run of at least eight digits, optionally `+`-prefixed and broken up by
         * spaces, dots, dashes or parentheses — which covers E.164 (`+224621234567`)
         * and the local groupings people actually paste.
         *
         * The leading guard rejects a preceding `-` or `.` as well as a word
         * character, because a UUID's trailing group (`…-446655440000`) is otherwise
         * a twelve-digit run that matches — which would redact the correlation and
         * tenant ids this scrubber exists to preserve. The trailing guard stays on
         * word characters alone so an ordinary sentence-ending full stop after a
         * number is still scrubbed.
         *
         * Emails are scrubbed first so an address whose local part is a phone
         * number is removed whole rather than leaving a bare domain behind.
         */
        val PHONE = Regex("""(?<![\w.-])\+?\d[\d\s().-]{6,}\d(?!\w)""")

        const val EMAIL_PLACEHOLDER = "[redacted-email]"
        const val PHONE_PLACEHOLDER = "[redacted-phone]"
    }
}
