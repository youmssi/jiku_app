package com.jiku.invitation.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/**
 * Where a card's link left through (JIKU-282): each share button tags its link
 * with one of these, so visits and answers can be counted per network. An
 * unknown or missing tag counts as [OTHER], never lost.
 */
enum class CardSource(
    val tag: String,
) {
    WHATSAPP("whatsapp"),
    FACEBOOK("facebook"),
    TELEGRAM("telegram"),
    SHARE_SHEET("share"),
    LINK("link"),
    QR("qr"),
    OTHER("other"),
    ;

    companion object {
        fun of(tag: String?): CardSource = entries.firstOrNull { it.tag == tag?.trim()?.lowercase() } ?: OTHER
    }
}

/** Shares and visits of one card through one network. Counts only: nothing about a person. */
@Entity
@Table(name = "open_card_funnel")
class CardFunnelCounter(
    @Id
    val id: UUID,
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    val source: String,
    @Column(name = "shares", nullable = false)
    val shares: Long = 0,
    @Column(name = "visits", nullable = false)
    val visits: Long = 0,
) : BaseTenantEntity()

interface CardFunnelRepository : JpaRepository<CardFunnelCounter, UUID> {
    fun findByEventId(eventId: UUID): List<CardFunnelCounter>

    /** Creates the counter row once; concurrent first visits meet on the unique key. */
    @Modifying
    @Query(
        value =
            "INSERT INTO open_card_funnel (id, tenant_id, event_id, source, shares, visits) " +
                "VALUES (gen_random_uuid(), :tenantId, :eventId, :source, 0, 0) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun ensure(
        @Param("tenantId") tenantId: String,
        @Param("eventId") eventId: UUID,
        @Param("source") source: String,
    )

    @Modifying
    @Query("UPDATE CardFunnelCounter c SET c.shares = c.shares + 1 WHERE c.eventId = :eventId AND c.source = :source")
    fun addShare(
        @Param("eventId") eventId: UUID,
        @Param("source") source: String,
    )

    @Modifying
    @Query("UPDATE CardFunnelCounter c SET c.visits = c.visits + 1 WHERE c.eventId = :eventId AND c.source = :source")
    fun addVisit(
        @Param("eventId") eventId: UUID,
        @Param("source") source: String,
    )
}

/** One network's line in the organizer's funnel: links shared, card pages opened, answers given. */
data class CardFunnelRow(
    val source: String,
    val shares: Long,
    val visits: Long,
    val answers: Long,
    val coming: Long,
)
