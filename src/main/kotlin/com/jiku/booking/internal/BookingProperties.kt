package com.jiku.booking.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * Deposit-reservation configuration (JIKU-55). The deposit percentage and payee
 * details are configuration, not literals, exactly like [com.jiku.money.internal.ManualPaymentProperties] —
 * these are the same class of "where does the client send money" detail, kept
 * separate because the booking flow is a distinct product surface from the
 * concierge tier-unlock flow.
 */
@ConfigurationProperties(prefix = "booking")
data class BookingProperties(
    /** Fraction of the total quoted price collected as a deposit. */
    val depositRate: BigDecimal = BigDecimal("0.30"),
    /** Days before the event date the balance is due. */
    val balanceDueDaysBeforeEvent: Long = 7,
    /** IANA timezone used for the pre-filled draft event (JIKU-55). */
    val eventTimezone: String = "Africa/Conakry",
    /** Pays (ISO 3166-1 alpha-2) des clients — résolution fiscale des avatars (JIKU-75). */
    val refundCountry: String = "GN",
    val payeeName: String = "",
    val orangeMoneyNumber: String = "",
    val mtnMomoNumber: String = "",
)
