package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * A tenant's Organizer Pack (ADR 105), one row per tenant. Months run from
 * [startedAt] month by month; each paid period pushes [expiresAt] further.
 * [owedGuests] are those an event day sent past the month's allowance, paid
 * with the next renewal.
 */
@Entity
@Table(name = "organizer_pack")
class OrganizerPack(
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "owed_guests", nullable = false)
    var owedGuests: Long = 0

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

/** One month of a pack: what it sent and the extra guests bought for it. */
@Entity
@Table(name = "organizer_pack_month")
class OrganizerPackMonth(
    @Column(name = "month_start", nullable = false, updatable = false)
    val monthStart: Instant,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "used_guests", nullable = false)
    var usedGuests: Long = 0

    @Column(name = "extra_guests", nullable = false)
    var extraGuests: Long = 0
}

interface OrganizerPackRepository : JpaRepository<OrganizerPack, UUID> {
    /** The current tenant's pack, if it ever bought one. */
    fun findFirstByOrderByStartedAtAsc(): OrganizerPack?
}

interface OrganizerPackMonthRepository : JpaRepository<OrganizerPackMonth, UUID> {
    fun findFirstByMonthStart(monthStart: Instant): OrganizerPackMonth?
}
