package com.jiku.money.internal

/**
 * Lifecycle of a payment. A payment only grants what it paid for once it reaches
 * [SUCCEEDED] via the provider's server-to-server confirmation, or the payments
 * desk's confirmation of a transfer; any other status never changes what the
 * organization holds.
 */
enum class PaymentStatus {
    PENDING,
    SUCCEEDED,

    /** Rejected by the payments desk, or an online attempt the provider did not complete. */
    FAILED,

    /**
     * A manual request the organizer cancelled, or that a new request or an online
     * payment for the same purchase replaced (JIKU-307). The desk can still confirm
     * a transfer that arrives for it.
     */
    CANCELLED,

    /** A manual request with no transfer confirmed within the expiry delay (JIKU-307). */
    EXPIRED,
    ;

    /** A manual request in this status can still be confirmed when its transfer turns up. */
    val confirmable: Boolean get() = this == PENDING || this == CANCELLED || this == EXPIRED
}
