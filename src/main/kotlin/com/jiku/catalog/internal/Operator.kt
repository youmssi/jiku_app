package com.jiku.catalog.internal

import com.jiku.catalog.OperatorAction
import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Someone who works for an organization without an account (JIKU-116): at the
 * door, at the counter or at the till. They act through one signed link; this
 * row carries what that link may reach ([eventIds], [serviceIds]) and do
 * ([actions]), and governs its immediate revocation. Every action taken through
 * the link is attributed to [label].
 */
@Entity
@Table(name = "operator")
class Operator(
    @Column(name = "label", nullable = false, length = 80)
    var label: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    /** Short shareable code, resolved into a fresh signed link on each use. */
    @Column(name = "code", length = 10)
    var code: String? = null

    @ElementCollection
    @CollectionTable(name = "operator_event", joinColumns = [JoinColumn(name = "operator_id")])
    @Column(name = "event_id", nullable = false)
    var eventIds: MutableSet<UUID> = mutableSetOf()

    @ElementCollection
    @CollectionTable(name = "operator_service", joinColumns = [JoinColumn(name = "operator_id")])
    @Column(name = "service_id", nullable = false)
    var serviceIds: MutableSet<UUID> = mutableSetOf()

    @Column(name = "can_check_in", nullable = false)
    var canCheckIn: Boolean = false

    @Column(name = "can_queue", nullable = false)
    var canQueue: Boolean = false

    @Column(name = "can_collect", nullable = false)
    var canCollect: Boolean = false

    @Column(name = "revoked", nullable = false)
    var revoked: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    var actions: Set<OperatorAction>
        get() =
            buildSet {
                if (canCheckIn) add(OperatorAction.CHECK_IN)
                if (canQueue) add(OperatorAction.QUEUE)
                if (canCollect) add(OperatorAction.COLLECT)
            }
        set(value) {
            canCheckIn = OperatorAction.CHECK_IN in value
            canQueue = OperatorAction.QUEUE in value
            canCollect = OperatorAction.COLLECT in value
        }

    /** An operator with a service in scope takes a seat of the organization's subscription. */
    val billable: Boolean get() = !revoked && serviceIds.isNotEmpty()

    fun revoke(at: Instant = Instant.now()) {
        if (!revoked) {
            revoked = true
            revokedAt = at
        }
    }
}
