package com.jiku.messaging.internal

/** A rendered WhatsApp message ready for delivery by a provider adapter. */
data class WhatsAppMessage(
    val to: String,
    val body: String,
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
