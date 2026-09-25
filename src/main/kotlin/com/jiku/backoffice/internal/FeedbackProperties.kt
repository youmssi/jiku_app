package com.jiku.backoffice.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Feedback prompts (JIKU-133): how often one person may be asked for a rating. */
@ConfigurationProperties(prefix = "feedback")
data class FeedbackProperties(
    /** Minimum gap between two rating prompts for the same person. */
    val promptInterval: Duration = Duration.ofDays(7),
)
