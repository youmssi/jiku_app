package com.jiku.shared

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Data-retention policy configuration (JIKU-37). After [days] past an event's date,
 * its guests' personal data is anonymized by the scheduled job; [noticeDays] is how
 * far ahead of that cutoff the organizer dashboard warns. Lives in `shared` because
 * both the invitation retention job and the checkin dashboard read it.
 */
@ConfigurationProperties(prefix = "compliance.retention")
data class RetentionProperties(
    /** Retention window after the event date before anonymization (default ~12 months). */
    val days: Long = 365,
    /** How many days before the cutoff the dashboard shows the approaching-retention notice. */
    val noticeDays: Long = 30,
    /** Cron for the retention job (default: nightly at 03:30). */
    val cron: String = "0 30 3 * * *",
)
