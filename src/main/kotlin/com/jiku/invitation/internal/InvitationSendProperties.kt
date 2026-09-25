package com.jiku.invitation.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "invitation.send")
data class InvitationSendProperties(
    /** Frontend base URL used to build guest invitation links (APP_BASE_URL). */
    val appBaseUrl: String = "http://localhost:3000",
    /** This API's public URL, for the ticket QR image WhatsApp fetches (API_PUBLIC_URL, JIKU-143). */
    val apiPublicUrl: String = "http://localhost:8080",
)
