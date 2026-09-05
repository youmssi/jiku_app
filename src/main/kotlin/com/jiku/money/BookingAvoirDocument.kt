package com.jiku.money

import java.util.UUID

/** L'avoir (CREDIT_NOTE, JIKU-69) émis pour un remboursement d'acompte de réservation. */
data class BookingAvoirDocument(
    val invoiceId: UUID,
    val invoiceNumber: String,
)
