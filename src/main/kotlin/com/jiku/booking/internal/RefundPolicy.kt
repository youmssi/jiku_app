package com.jiku.booking.internal

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The cancellation refund policy on a booking's deposit (JIKU-55): more than 60
 * days before the event, the deposit is fully refundable; 30 to 60 days, half;
 * under 30 days, nothing — the date was blocked and cannot be resold on short
 * notice. A pure function so it is directly unit-testable across all three
 * windows without standing up the full booking flow.
 */
object RefundPolicy {
    fun refundRate(daysUntilEvent: Long): BigDecimal =
        when {
            daysUntilEvent > 60 -> BigDecimal.ONE
            daysUntilEvent >= 30 -> BigDecimal("0.50")
            else -> BigDecimal.ZERO
        }

    fun refundAmountMinor(
        depositAmountMinor: Long,
        today: LocalDate,
        eventDate: LocalDate,
    ): Long {
        val daysUntilEvent = ChronoUnit.DAYS.between(today, eventDate)
        val rate = refundRate(daysUntilEvent)
        return BigDecimal(depositAmountMinor).multiply(rate).setScale(0, RoundingMode.HALF_UP).toLong()
    }
}
