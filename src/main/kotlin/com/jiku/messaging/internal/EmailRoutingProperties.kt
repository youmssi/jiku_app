package com.jiku.messaging.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Daily send caps for [RoutingEmailSender] (JIKU-62). Kept comfortably under
 * each provider's actual free-tier ceiling (Resend 100/day, Brevo 300/day) and
 * under their naive sum: the two free tiers are not reliably additive in
 * practice once a safety margin is applied, hence a separate global cap.
 */
@ConfigurationProperties(prefix = "jiku.mail.routing")
data class EmailRoutingProperties(
    val resendDailyCap: Int = 100,
    val brevoDailyCap: Int = 300,
    val globalDailyCap: Int = 350,
)
