package com.jiku.tenant.internal

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * How the organization's clients pay it (ADR 104, §4): Mobile Money numbers shown
 * to the client (level 1) and the organization's own payment link (level 2). Jikū
 * displays them and never handles the money.
 */
@Embeddable
class TenantPaymentMethods(
    /** The name the client sees on their Mobile Money confirmation. */
    @Column(name = "payment_payee_name")
    var payeeName: String? = null,
    @Column(name = "payment_orange_money")
    var orangeMoneyNumber: String? = null,
    @Column(name = "payment_mtn_momo")
    var mtnMomoNumber: String? = null,
    @Column(name = "payment_wave")
    var waveNumber: String? = null,
    @Column(name = "payment_link_url")
    var paymentLinkUrl: String? = null,
) {
    fun hasAny(): Boolean = listOf(orangeMoneyNumber, mtnMomoNumber, waveNumber, paymentLinkUrl).any { !it.isNullOrBlank() }

    /** Same payee and same methods; an embeddable compares by reference otherwise. */
    fun sameAs(other: TenantPaymentMethods?): Boolean =
        other != null &&
            payeeName == other.payeeName &&
            orangeMoneyNumber == other.orangeMoneyNumber &&
            mtnMomoNumber == other.mtnMomoNumber &&
            waveNumber == other.waveNumber &&
            paymentLinkUrl == other.paymentLinkUrl
}
