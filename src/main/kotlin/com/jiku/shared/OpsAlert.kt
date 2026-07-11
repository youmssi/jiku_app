package com.jiku.shared

/**
 * A platform-operations alert (JIKU-43): published by any module that needs the
 * operating team's attention and delivered by the notification module to the
 * configured ops/sales mailbox. Content only — no recipient, so publishers never
 * hold notification configuration.
 */
data class OpsAlert(
    val subject: String,
    val message: String,
)
