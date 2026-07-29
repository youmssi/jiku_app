package com.jiku.notification.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface EmailQuotaCounterRepository : JpaRepository<EmailQuotaCounter, UUID> {
    /**
     * Atomically increments [EmailQuotaCounter.PROVIDER_RESEND]/etc.'s count for
     * [day] and returns the number of rows changed (1 = reserved successfully,
     * 0 = [cap] already reached today, nothing changed) — a single round trip,
     * so two concurrent callers can never both succeed past the cap. `id` is
     * only used for the first insert of the day; an existing row's id is kept.
     */
    @Modifying
    @Query(
        value =
            "INSERT INTO email_quota_counter (id, provider, day, sent_count) VALUES (:id, :provider, :day, 1) " +
                "ON CONFLICT (provider, day) DO UPDATE SET sent_count = email_quota_counter.sent_count + 1 " +
                "WHERE email_quota_counter.sent_count < :cap",
        nativeQuery = true,
    )
    fun tryReserve(
        @Param("id") id: UUID,
        @Param("provider") provider: String,
        @Param("day") day: LocalDate,
        @Param("cap") cap: Int,
    ): Int

    fun findByProviderAndDay(
        provider: String,
        day: LocalDate,
    ): EmailQuotaCounter?
}
