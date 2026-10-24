package com.jiku.messaging.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The provider chain and daily caps of [RoutingEmailSender] (JIKU-62, JIKU-275).
 * Each cap stays under the provider's free-tier ceiling (useSend 100/day, Resend
 * 100/day, Brevo 300/day), and the global cap under their sum: free tiers are not
 * reliably additive once a safety margin is applied.
 */
@ConfigurationProperties(prefix = "jiku.mail.routing")
data class EmailRoutingProperties(
    /** Providers tried in this order: any of `usesend`, `resend`, `brevo`. */
    val order: List<String> = listOf("resend", "brevo"),
    val usesendDailyCap: Int = 100,
    val resendDailyCap: Int = 100,
    val brevoDailyCap: Int = 300,
    val globalDailyCap: Int = 350,
    /** Past every cap, send through useSend's paid plan instead of waiting for the next day. */
    val usesendOverflow: Boolean = false,
)
