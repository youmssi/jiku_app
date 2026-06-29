package com.jiku.notification.internal

/** A rendered WhatsApp message ready for delivery by a provider adapter. */
data class WhatsAppMessage(
    val to: String,
    val body: String,
)

/**
 * Provider adapter port for WhatsApp. The concrete BSP (Twilio / 360dialog) is
 * chosen later (JIKU-17 interactive step); until then a logging adapter stands in.
 * A real adapter should throw on delivery failure so the caller can retry.
 */
interface WhatsAppSender {
    fun send(message: WhatsAppMessage)
}
