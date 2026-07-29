package com.jiku.notification.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.util.UUID

/**
 * One provider's send count for one calendar day (JIKU-62), incremented only
 * through [EmailQuotaCounterRepository.tryReserve]'s atomic conditional upsert
 * so the daily cap is never exceeded even under concurrent sends, and the count
 * survives an app restart (unlike an in-memory counter). Platform-wide, not
 * tenant-scoped — the cap is per provider account, shared by every tenant on
 * the platform transport. [provider] is one of [PROVIDER_RESEND] /
 * [PROVIDER_BREVO] / [PROVIDER_GLOBAL].
 */
@Entity
@Table(
    name = "email_quota_counter",
    uniqueConstraints = [UniqueConstraint(name = "uq_email_quota_counter_provider_day", columnNames = ["provider", "day"])],
)
class EmailQuotaCounter(
    @Column(name = "provider", nullable = false)
    val provider: String,
    @Column(name = "day", nullable = false)
    val day: LocalDate,
    @Column(name = "sent_count", nullable = false)
    val sentCount: Long,
) {
    @Id
    var id: UUID? = null

    companion object {
        const val PROVIDER_RESEND = "RESEND"
        const val PROVIDER_BREVO = "BREVO"
        const val PROVIDER_GLOBAL = "GLOBAL"
    }
}
