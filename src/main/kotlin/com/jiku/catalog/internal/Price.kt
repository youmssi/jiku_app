package com.jiku.catalog.internal

import com.jiku.shared.TenantContext
import com.jiku.shared.TenantCurrency
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * What a client pays the organization for a ticket (ADR 104, §3). Absent means
 * free: a free ticket has no price and no currency. The money never goes through
 * Jikū; this only tells the client how much to pay.
 */
@Embeddable
class Price(
    @Column(name = "price_minor")
    val amountMinor: Long,
    /** Always the organization's currency (JIKU-107). */
    @Column(name = "price_currency", length = 3)
    val currency: String,
) {
    init {
        require(amountMinor > 0) { "A price is positive; a free ticket has no price" }
    }
}

/** The calling organization's currency: every price it sets is in it. */
internal fun TenantCurrency.ofCurrentTenant(): String = of(requireNotNull(TenantContext.get()) { "Pricing requires a tenant" })

/** When a service's client pays the organization (ADR 104, §3). */
enum class PaymentRule {
    FREE,

    /** No ticket until the payment is confirmed. */
    BEFORE,

    /** The operator asks for the payment when the service ends. */
    AFTER_SERVICE,
}

/**
 * The price a payment rule calls for: none when the service is free, a positive
 * amount otherwise. Refused with 400 when the two disagree.
 */
internal fun priceFor(
    rule: PaymentRule,
    amountMinor: Long?,
    currency: () -> String,
): Price? =
    when {
        rule == PaymentRule.FREE && amountMinor != null ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A free service has no price")
        rule == PaymentRule.FREE -> null
        amountMinor == null || amountMinor <= 0 ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A paid service needs a positive price")
        else -> Price(amountMinor, currency())
    }
