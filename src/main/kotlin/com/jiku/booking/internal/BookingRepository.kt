package com.jiku.booking.internal

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** Not tenant-scoped (see [Booking]), so plain derived queries already read platform-wide. */
interface BookingRepository : JpaRepository<Booking, UUID> {
    fun findByAccessTokenHash(hash: String): Booking?

    fun findByStatusOrderByCreatedAtDesc(
        status: BookingStatus,
        pageable: Pageable,
    ): List<Booking>

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<Booking>
}
