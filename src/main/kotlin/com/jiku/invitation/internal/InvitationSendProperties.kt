package com.jiku.invitation.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "invitation.send")
data class InvitationSendProperties(
    /** Frontend base URL used to build guest invitation links (APP_BASE_URL). */
    val appBaseUrl: String = "http://localhost:3000",
    /** Maximum delivery attempts before an invitation is marked permanently FAILED. */
    val maxAttempts: Int = 3,
)
