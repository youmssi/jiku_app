package com.jiku.shared

/**
 * What a client owes the organization for a ticket, fixed when the ticket is
 * issued (ADR 104, §3). The catalog prices it, the events that issue tickets
 * carry it, and the ticket records it. The money goes straight to the
 * organization; Jikū never handles it.
 */
data class ClientCharge(
    val amountMinor: Long,
    val currency: String,
    /** Payable when the service ends rather than before the ticket is used. */
    val dueAfterService: Boolean = false,
) {
    init {
        require(amountMinor > 0) { "A charge is positive; a free ticket carries none" }
    }
}
