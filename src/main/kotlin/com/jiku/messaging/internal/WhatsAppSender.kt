package com.jiku.messaging.internal

/** A rendered WhatsApp message ready for delivery by a provider adapter. */
data class WhatsAppMessage(
    val to: String,
    val body: String,
    /** Quick-reply buttons under the text (JIKU-143); a tap comes back through the webhook with the button's id. */
    val buttons: List<WhatsAppButton> = emptyList(),
    /** A public PNG or JPEG shown above the text, such as a ticket's QR code (JIKU-143). */
    val imageUrl: String? = null,
)

/** One quick-reply button: [id] is what the webhook receives, [title] what the guest sees (20 characters at most). */
data class WhatsAppButton(
    val id: String,
    val title: String,
)

/**
 * Provider adapter port for WhatsApp. The transport is selected with
 * `jiku.whatsapp.transport`: [LoggingWhatsAppSender] (`log`, default) or
 * [MetaCloudWhatsAppSender] (`meta`, the WhatsApp Cloud API). Adapters throw
 * [WhatsAppDeliveryException] on failure so the orchestration retry engages.
 * Further providers (Twilio, 360dialog, …) plug in as additional adapters.
 */
interface WhatsAppSender {
    fun send(message: WhatsAppMessage)
}
