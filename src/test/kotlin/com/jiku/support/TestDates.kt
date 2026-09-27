package com.jiku.support

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Dates relative to today, so tests never age into the past. Services book at
 * most `max-horizon-days` ahead and at least `min-horizon-minutes` from now, so
 * the reference Monday is always within the coming days and never today or
 * tomorrow: a booking on it stays outside the 24-hour cancellation deadline. Test
 * services and events run in UTC zones (Africa/Conakry, Africa/Abidjan).
 */
object TestDates {
    val MONDAY: LocalDate =
        LocalDate
            .now(ZoneOffset.UTC)
            .plusDays(2)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
    val TUESDAY: LocalDate = MONDAY.plusDays(1)

    /** Next year: event start dates fall in its December, always ahead. */
    val EVENT_YEAR: Int = LocalDate.now(ZoneOffset.UTC).year + 1
}
