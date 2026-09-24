package com.jiku.booking

import com.jiku.booking.internal.Booking
import com.jiku.booking.internal.BookingEventType
import com.jiku.booking.internal.BookingRepository
import com.jiku.booking.internal.BookingTokens
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** A deposit reservation opened before JIKU-115, with the raw token its prospect holds. */
data class OpenBooking(
    val id: String,
    val accessToken: String,
)

/**
 * Seeds the deposit reservations still open since JIKU-115 closed new ones: a
 * BRONZE tier (150 000) with its 30% deposit (45 000) awaiting payment.
 */
fun BookingRepository.openBooking(
    email: String,
    eventDate: LocalDate = LocalDate.now().plusYears(1),
): OpenBooking {
    val token = UUID.randomUUID().toString()
    val booking =
        save(
            Booking(
                customerName = "Test Customer",
                customerPhone = "+224600000000",
                customerEmail = email,
                eventType = BookingEventType.MARIAGE,
                eventDate = eventDate,
                guestCountEstimate = 150,
                tier = "BRONZE",
                totalAmountMinor = 150_000,
                depositRate = BigDecimal("0.30"),
                depositAmountMinor = 45_000,
                balanceAmountMinor = 105_000,
                balanceDueDate = eventDate.minusDays(7),
                accessTokenHash = BookingTokens.hash(token),
            ),
        )
    return OpenBooking(id = booking.id.toString(), accessToken = token)
}
