package com.jiku.ticket.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AppointmentReminderRepository : JpaRepository<AppointmentReminder, UUID> {
    fun existsByTicketIdAndOffsetMinutes(
        ticketId: UUID,
        offsetMinutes: Int,
    ): Boolean

    /**
     * Rendez-vous à rappeler pour un décalage donné (JIKU-89) : billets de
     * service à venir (créneau dans la fenêtre [now, now + offset]), toujours
     * réservables (ISSUED), avec un client joignable, et sans ligne de rappel
     * déjà posée pour ce décalage. HQL pour que le prédicat tenant s'applique.
     */
    @Query(
        "SELECT t FROM Ticket t WHERE t.serviceId = :serviceId " +
            "AND t.kind = com.jiku.ticket.internal.TicketKind.APPOINTMENT " +
            "AND t.status = com.jiku.ticket.internal.TicketStatus.ISSUED " +
            "AND t.clientPhone IS NOT NULL AND t.clientName IS NOT NULL " +
            "AND t.startsAt IS NOT NULL AND t.startsAt > :now AND t.startsAt <= :until " +
            "AND NOT EXISTS (SELECT r FROM AppointmentReminder r " +
            "WHERE r.ticketId = t.id AND r.offsetMinutes = :offsetMinutes)",
    )
    fun findRemindableForOffset(
        @Param("serviceId") serviceId: UUID,
        @Param("offsetMinutes") offsetMinutes: Int,
        @Param("now") now: Instant,
        @Param("until") until: Instant,
    ): List<Ticket>

    /** Services avec rappels activés, tous tenants (balayage plateforme). */
    @Query(
        value =
            "SELECT sc.service_id AS serviceId, sc.tenant_id AS tenantId, " +
                "sc.reminder_channel AS channel, sc.reminder_offsets_minutes AS offsets, " +
                "s.timezone AS timezone " +
                "FROM service_config sc JOIN service s ON s.id = sc.service_id " +
                "WHERE sc.reminder_channel IS NOT NULL AND sc.reminder_channel <> 'NONE'",
        nativeQuery = true,
    )
    fun findReminderEnabledServices(): List<ReminderServiceRef>

    /** Rappels retenus par une garde-fou, tous tenants (à rejouer). */
    @Query(
        value =
            "SELECT id AS reminderId, tenant_id AS tenantId " +
                "FROM appointment_reminder WHERE status = 'QUEUED'",
        nativeQuery = true,
    )
    fun findQueuedReminders(): List<QueuedReminderRef>

    /** Fuseau du service d'un rappel (rejoué sans re-balayer les services). */
    @Query(
        value = "SELECT timezone FROM service WHERE id = :serviceId",
        nativeQuery = true,
    )
    fun serviceTimezone(
        @Param("serviceId") serviceId: UUID,
    ): String?
}

interface ReminderServiceRef {
    val serviceId: UUID
    val tenantId: String
    val channel: String
    val offsets: String?
    val timezone: String
}

interface QueuedReminderRef {
    val reminderId: UUID
    val tenantId: String
}
