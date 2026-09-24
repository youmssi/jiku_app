package com.jiku.booking.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Deposit-reservation configuration (JIKU-55), kept for the reservations still
 * open since JIKU-115. The payee details are configuration, not literals, like
 * [com.jiku.money.internal.ManualPaymentProperties].
 */
@ConfigurationProperties(prefix = "booking")
data class BookingProperties(
    /** IANA timezone used for the pre-filled draft event (JIKU-55). */
    val eventTimezone: String = "Africa/Conakry",
    /** Pays (ISO 3166-1 alpha-2) des clients — résolution fiscale des avatars (JIKU-75). */
    val refundCountry: String = "GN",
    val payeeName: String = "",
    val orangeMoneyNumber: String = "",
    val mtnMomoNumber: String = "",
)
