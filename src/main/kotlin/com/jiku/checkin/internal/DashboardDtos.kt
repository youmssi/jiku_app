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
    /** Présent uniquement si l'organisateur a configuré un quorum (JIKU-94). */
    val quorum: QuorumView? = null,
    val deliverability: DeliverabilityFlag,
    val usage: UsageSummary,
)

/**
 * The event's billing usage against its unlocked allowance (JIKU-32), shown to the
 * organizer before they hit a paywall so consumption is never a surprise.
 */
data class UsageSummary(
    val invited: Long,
    val allowance: Long,
    val remaining: Long,
    val tier: String,
    val withinAllowance: Boolean,
    /** Present only when the event's guest data is approaching its retention cutoff. */
    val dataRetention: DataRetentionNotice?,
)

/**
 * Advance notice that this event's guest personal data will be anonymized on
 * [anonymizeOn] under the retention policy (JIKU-37).
 */
data class DataRetentionNotice(
    val anonymizeOn: java.time.Instant,
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

/**
 * État du quorum tel que le tableau de bord le montre (JIKU-94).
 *
 * [reached] est l'état à l'instant présent ; [reachedAt] est la première
 * atteinte, conservée même si des départs font retomber le compte. Les deux sont
 * vraies, et l'écran doit montrer les deux.
 */
data class QuorumView(
    val required: Long,
    val current: Long,
    val reached: Boolean,
    val reachedAt: java.time.Instant?,
)
