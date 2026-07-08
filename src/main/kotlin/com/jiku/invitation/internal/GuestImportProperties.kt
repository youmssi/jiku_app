package com.jiku.invitation.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "invitation.import")
data class GuestImportProperties(
    val maxRows: Int = 20000,
    val disposableEmailDomains: List<String> =
        listOf(
            "mailinator.com",
            "guerrillamail.com",
            "10minutemail.com",
            "trashmail.com",
            "tempmail.com",
        ),
)
