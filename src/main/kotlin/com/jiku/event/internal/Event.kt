package com.jiku.event.internal

import com.jiku.event.InvitationChannel
import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * An event owned by a tenant. Tenant-scoped (extends [BaseTenantEntity]), so the
 * persistence-layer tenant filter isolates it automatically. Start/end times are
 * stored as UTC instants; [timezone] is the event's IANA zone for display.
 */
@Entity
@Table(name = "event")
class Event(
    @Column(name = "name", nullable = false)
    var name: String,
    @Column(name = "description", columnDefinition = "text")
    var description: String? = null,
    @Column(name = "start_date_time")
    var startDateTime: Instant? = null,
    @Column(name = "end_date_time")
    var endDateTime: Instant? = null,
    @Column(name = "timezone", nullable = false)
    var timezone: String,
    @Column(name = "location")
    var location: String? = null,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: EventStatus = EventStatus.DRAFT

    @Embedded
    var settings: EventSettings = EventSettings()

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_invitation_channel", joinColumns = [JoinColumn(name = "event_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    var invitationChannels: MutableSet<InvitationChannel> = mutableSetOf()

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
