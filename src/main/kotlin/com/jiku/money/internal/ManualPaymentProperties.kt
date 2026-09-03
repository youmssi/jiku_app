package com.jiku.money.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Payee details for the manual (concierge) payment flow (JIKU-41): where a client
 * sends their transfer. Configuration, never literals — each environment points
 * at its own accounts. Details flow one way (platform → client); the platform
 * never collects or stores any client payment credential.
 */
@ConfigurationProperties(prefix = "billing.manual")
data class ManualPaymentProperties(
    /** Account holder name shown with the payment instructions. */
    val payeeName: String = "",
    /** Mobile Money merchant/receiving number. */
    val mobileMoneyNumber: String = "",
    /** Operator of the receiving number (e.g. "Orange Money", "Wave"). */
    val mobileMoneyOperator: String = "",
    /** Bank transfer details as displayable free text; blank hides the option. */
    val bankDetails: String = "",
)
