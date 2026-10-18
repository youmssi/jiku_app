package com.jiku.messaging.internal

/** Meta Cloud API error codes Jikū explains in words instead of a bare number (JIKU-269). */
object MetaErrors {
    /** The sending WhatsApp Business account has no valid payment method. */
    const val PAYMENT_ISSUE = 131042

    /** Why [code] stopped a message, for the organizer's delivery status; null when Jikū has no wording for it. */
    fun explain(code: Int?): String? =
        when (code) {
            PAYMENT_ISSUE ->
                "Not sent: the WhatsApp Business account has no valid payment method. " +
                    "Its owner adds one in Meta Business Suite, under Billing, and sending resumes."
            else -> null
        }
}
