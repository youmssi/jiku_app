package com.jiku.catalog.internal

import com.jiku.catalog.DeliveryMode
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 24)
    var deliveryMode: DeliveryMode = DeliveryMode.LINK,
)
