package com.jiku.shared

import java.util.UUID

/**
 * The paywall gate (JIKU-34), exposed in `shared` so the invitation module can
 * enforce the allowance without depending on the billing module — billing already
 * depends on invitation for metering, and a reverse dependency would form a cycle.
 * Billing provides the implementation; invitation consumes this interface.
 */
interface UsageAllowanceGate {
    /**
     * The currently unlocked ceiling for an event: the maximum number of distinct
     * guests that may be invited (free tier by default, raised by a paid unlock).
     */
    fun allowanceCeiling(eventId: UUID): Long
}
