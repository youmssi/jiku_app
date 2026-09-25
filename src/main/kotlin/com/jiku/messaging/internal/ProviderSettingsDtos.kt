package com.jiku.messaging.internal

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

/** Decrypted credential shapes stored (encrypted) per tenant and channel. */
data class ResendCredentials(
    val apiKey: String,
    val from: String,
    val fromName: String? = null,
)

data class MetaCloudCredentials(
    val phoneNumberId: String,
    val accessToken: String,
    val templateName: String? = null,
    val templateLanguage: String = "fr",
    val buttonsTemplateName: String? = null,
    val imageTemplateName: String? = null,
    /** Set when the number came through Embedded Signup (ADR 105). */
    val wabaId: String? = null,
    val displayPhoneNumber: String? = null,
    val verifiedName: String? = null,
    /** The two-step verification PIN the number was registered with. */
    val pin: String? = null,
)

data class UpdateEmailProviderRequest(
    @field:NotBlank
    val apiKey: String,
    @field:NotBlank
    @field:Email
    val from: String,
    val fromName: String? = null,
)

data class UpdateWhatsAppProviderRequest(
    @field:NotBlank
    val accessToken: String,
    @field:NotBlank
    val phoneNumberId: String,
    val templateName: String? = null,
    val templateLanguage: String? = null,
)

/** Read views never carry a full secret — only the last characters for recognition. */
data class EmailProviderView(
    val configured: Boolean,
    val provider: String? = null,
    val from: String? = null,
    val fromName: String? = null,
    val apiKeyMasked: String? = null,
)

data class WhatsAppProviderView(
    val configured: Boolean,
    val provider: String? = null,
    val phoneNumberId: String? = null,
    val accessTokenMasked: String? = null,
    val templateName: String? = null,
    val templateLanguage: String? = null,
    /**
     * Whether the organization's offer includes its own number (ADR 105).
     * Saved credentials are used only while it does; otherwise sends go
     * through the platform number.
     */
    val allowed: Boolean = false,
    /** The number as Meta shows it, for a number connected through Embedded Signup. */
    val displayPhoneNumber: String? = null,
    val verifiedName: String? = null,
)

/** What the web needs to open Meta's Embedded Signup window; [enabled] is false until the Meta app is configured. */
data class EmbeddedSignupConfig(
    val enabled: Boolean,
    val appId: String? = null,
    val configId: String? = null,
    val graphVersion: String? = null,
)

/** What Meta's window hands back: the code to exchange, and the account and number the organizer chose. */
data class CompleteEmbeddedSignupRequest(
    @field:NotBlank
    val code: String,
    @field:NotBlank
    val wabaId: String,
    @field:NotBlank
    val phoneNumberId: String,
)

data class ProviderSettingsResponse(
    val email: EmailProviderView,
    val whatsapp: WhatsAppProviderView,
)

data class TestSendRequest(
    @field:NotBlank
    val recipient: String,
)

data class TestSendResponse(
    val delivered: Boolean,
    val usingTenantProvider: Boolean,
    val error: String? = null,
)

internal fun maskSecret(secret: String): String = if (secret.length <= 4) "••••" else "••••" + secret.takeLast(4)
