package com.jiku.catalog.internal

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.Instant

/**
 * Configurable event behavior. The feature logic behind these flags (seating,
 * transfer, overbooking) is built in later epics; this story only models the
 * configuration. Booleans are non-null with safe MVP defaults.
 */
@Embeddable
class EventSettings(
    @Column(name = "transfer_allowed", nullable = false)
    var transferAllowed: Boolean = false,
    @Column(name = "transfer_deadline")
    var transferDeadline: Instant? = null,
    @Column(name = "overbooking_allowed", nullable = false)
    var overbookingAllowed: Boolean = false,
    @Column(name = "max_overbooking_count")
    var maxOverbookingCount: Int? = null,
)
