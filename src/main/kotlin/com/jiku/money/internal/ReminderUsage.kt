package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

/** WhatsApp appointment reminders a tenant sent in one calendar month (ADR 105). */
@Entity
@Table(name = "whatsapp_reminder_usage")
class ReminderUsage(
    @Column(name = "month_start", nullable = false, updatable = false)
    val monthStart: LocalDate,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "sent", nullable = false)
    var sent: Int = 0
}

interface ReminderUsageRepository : JpaRepository<ReminderUsage, UUID> {
    fun findFirstByMonthStart(monthStart: LocalDate): ReminderUsage?
}
