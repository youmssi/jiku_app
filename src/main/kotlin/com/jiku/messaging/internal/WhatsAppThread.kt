package com.jiku.messaging.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * One invitation sent by WhatsApp, kept so the guest's replies find their way
 * back (JIKU-143). A reply reaches the platform webhook with no tenant, so the
 * row is deliberately outside the tenant filter and holds only the routing: the
 * invitation, its tenant, the number it went to (digits only, as Meta reports
 * the sender) and the language to answer in.
 */
@Entity
@Table(name = "whatsapp_thread")
class WhatsAppThread(
    @Id
    @Column(name = "invitation_id")
    val invitationId: UUID,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: String,
    @Column(name = "phone", nullable = false)
    val phone: String,
    @Column(name = "language", nullable = false)
    val language: String,
    @Column(name = "sent_at", nullable = false)
    var sentAt: Instant = Instant.now(),
)

interface WhatsAppThreadRepository : JpaRepository<WhatsAppThread, UUID> {
    fun findFirstByPhoneOrderBySentAtDesc(phone: String): WhatsAppThread?
}

/** A number that wrote STOP (JIKU-143): nothing more goes to it by WhatsApp until it writes START. */
@Entity
@Table(name = "whatsapp_opt_out")
class WhatsAppOptOut(
    @Id
    @Column(name = "phone")
    val phone: String,
    @Column(name = "opted_out_at", nullable = false)
    val optedOutAt: Instant = Instant.now(),
)

interface WhatsAppOptOutRepository : JpaRepository<WhatsAppOptOut, String>

/** The number as Meta reports a sender: digits only, no leading plus. */
internal fun whatsAppDigits(phone: String): String = phone.filter { it.isDigit() }
