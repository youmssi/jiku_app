package com.jiku.catalog.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Question personnalisée posée à l'invité au moment de répondre (JIKU-77) : menu,
 * transport, table… Tenant-scopée, rattachée à un événement. Les réponses seront
 * capturées lors du RSVP puis exportées.
 */
@Entity
@Table(name = "event_question")
class EventQuestion(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "prompt", nullable = false, length = 500)
    var prompt: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "required", nullable = false)
    var required: Boolean = false

    @Column(name = "position", nullable = false)
    var position: Int = 0

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
