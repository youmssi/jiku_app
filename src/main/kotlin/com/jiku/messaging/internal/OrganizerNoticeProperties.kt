package com.jiku.messaging.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** How long the notification bell keeps a notice (JIKU-314), and how often old ones are removed. */
@ConfigurationProperties(prefix = "notification.notices")
data class OrganizerNoticeProperties(
    val retention: Duration = Duration.ofDays(180),
    val purgeCheck: Duration = Duration.ofHours(6),
)
