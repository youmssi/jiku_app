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
