package com.jiku.checkin.internal

import com.jiku.invitation.ChannelBreakdown

/**
 * Detailed, decision-oriented figures for one event, distinct from [DashboardResponse]'s
 * live snapshot: trends over time rather than current totals (JIKU-26 follow-up).
 */
data class AnalyticsResponse(
    val checkInTimeline: List<TimeBucket>,
    val channelBreakdown: List<ChannelBreakdown>,
    val guestGrowth: List<DateCount>,
)

/** A labeled point on the check-in timeline, bucketed by hour in the event's own timezone. */
data class TimeBucket(
    val label: String,
    val count: Long,
)

/** Guests imported on a given calendar date (UTC), for the pre-event growth chart. */
data class DateCount(
    val date: String,
    val count: Long,
)
