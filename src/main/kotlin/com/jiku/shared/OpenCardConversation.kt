package com.jiku.shared

import java.time.Instant

/**
 * A message written or tapped on the Jikū WhatsApp number dedicated to cards
 * (JIKU-185, ADR 106). Published by the messaging module, which owns the
 * webhook; the invitation module runs the conversation. [from] is the number,
 * digits only; [profileName] the name the person shows in WhatsApp.
 */
data class OpenCardInbound(
    val from: String,
    val profileName: String?,
    val text: String? = null,
    val buttonId: String? = null,
)

/**
 * What to answer on the cards number (JIKU-185), inside the conversation the
 * person just opened, which Meta does not charge. [key] and each button's
 * title key name texts of the message catalog, rendered in [language]; when
 * [eventStart] is set, it is written in [eventTimezone] as `when`.
 */
data class OpenCardReply(
    val phone: String,
    val language: String,
    val key: String,
    val values: Map<String, String> = emptyMap(),
    val buttons: List<OpenCardButton> = emptyList(),
    val imageUrl: String? = null,
    val eventStart: Instant? = null,
    val eventTimezone: String? = null,
)

data class OpenCardButton(
    val id: String,
    val titleKey: String,
)
