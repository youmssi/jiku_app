package com.jiku.billing.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Trial sweep configuration (JIKU-42). */
@ConfigurationProperties(prefix = "billing.trial")
data class TrialProperties(
    /** How far before expiry the organizer's heads-up email goes out. */
    val noticeLead: Duration = Duration.ofDays(3),
)
