package com.jiku.booking.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BookingRefundRepository : JpaRepository<BookingRefund, UUID> {
    fun findByDeclarationId(declarationId: UUID): List<BookingRefund>

    fun findByBookingIdOrderByExecutedAtDesc(bookingId: UUID): List<BookingRefund>
}
