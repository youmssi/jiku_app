package com.jiku.checkin.internal

/**
 * Live metrics for an organizer's event dashboard (JIKU-26). Pre-event figures
 * (invited / confirmed / declined / pending) and the day-of figure (checkedIn)
 * are presented together; [entrances] breaks check-ins down by the label that
 * performed them, including validator links with no check-ins yet.
 */
data class DashboardResponse(
    val eventName: String,
    val eventStatus: String,
    val totalGuests: Long,
    val invited: Long,
    val confirmed: Long,
    val declined: Long,
    val pending: Long,
    val checkedIn: Long,
    val entrances: List<EntranceCount>,
    val deliverability: DeliverabilityFlag,
)

/**
 * The tenant's recent email deliverability, surfaced so an organizer whose own
 * list is bouncing sees a clear explanation rather than silent under-delivery.
 */
data class DeliverabilityFlag(
    val bounceRatePercent: Int,
    val warn: Boolean,
)

data class EntranceCount(
    val label: String,
    val checkedIn: Long,
)
