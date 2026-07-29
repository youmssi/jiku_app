package com.jiku.booking.internal

enum class BookingEventType {
    MARIAGE,
    BAPTEME,
    SALLE,
    SEMINAIRE,
    AUTRE,
}

/**
 * DRAFT is never persisted today (reservations are created with an amount and
 * immediately await payment) but is kept for a future quote-before-commit step.
 */
enum class BookingStatus {
    DRAFT,
    AWAITING_DEPOSIT,
    DEPOSIT_PAID,
    AWAITING_BALANCE,
    FULLY_PAID,
    CANCELLED,
    REFUNDED,
}

enum class PaymentDeclarationKind {
    DEPOSIT,
    BALANCE,
}

enum class MobileMoneyOperator {
    ORANGE_MONEY,
    MTN_MOMO,
}

enum class PaymentVerificationStatus {
    PENDING,
    VERIFIED,
    REJECTED,
    DUPLICATE,
}
