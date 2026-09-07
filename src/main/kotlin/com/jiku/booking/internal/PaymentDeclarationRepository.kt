package com.jiku.booking.internal

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentDeclarationRepository : JpaRepository<PaymentDeclaration, UUID> {
    fun findByBookingIdOrderByDeclaredAtDesc(bookingId: UUID): List<PaymentDeclaration>

    /** L'acompte vérifié le plus récent d'une réservation (JIKU-75). */
    fun findFirstByBookingIdAndKindAndVerificationStatusOrderByDeclaredAtDesc(
        bookingId: UUID,
        kind: PaymentDeclarationKind,
        status: PaymentVerificationStatus,
    ): PaymentDeclaration?

    fun existsByTransactionReferenceAndVerificationStatusNot(
        transactionReference: String,
        excludedStatus: PaymentVerificationStatus,
    ): Boolean

    fun findByVerificationStatusOrderByDeclaredAtDesc(
        status: PaymentVerificationStatus,
        pageable: Pageable,
    ): List<PaymentDeclaration>

    fun findAllByOrderByDeclaredAtDesc(pageable: Pageable): List<PaymentDeclaration>
}
