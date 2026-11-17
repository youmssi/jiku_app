package com.jiku.money.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Settings of gifts (JIKU-315). */
@ConfigurationProperties(prefix = "billing.gifts")
data class GiftProperties(
    /** How long an offered gift waits to be activated. */
    val claimWindow: Duration = Duration.ofDays(30),
    /** How long before the claim date the organizer is reminded. */
    val claimNoticeLead: Duration = Duration.ofDays(3),
    /** How often unclaimed gifts are looked for. */
    val check: Duration = Duration.ofHours(1),
    /** Most days of guests a single gift may lend. */
    val maxDays: Int = 90,
    /** Most months of a plan or pack a single gift may give. */
    val maxMonths: Int = 24,
)
