package com.jiku.checkin.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.OperatorModuleApi
import com.jiku.invitation.InvitationModuleApi
import com.jiku.messaging.NotificationModuleApi
import com.jiku.money.BillingModuleApi
import com.jiku.shared.RetentionProperties
import com.jiku.ticket.TicketingModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Aggregates an event's live dashboard metrics from the modules that own them:
 * guest/RSVP counts from invitation, attendance from ticketing, and the
 * per-entrance check-in breakdown from operator labels. Read-only; safe to poll.
 */
@Service
class DashboardService(
    private val events: EventModuleApi,
    private val invitation: InvitationModuleApi,
    private val ticketing: TicketingModuleApi,
    private val notifications: NotificationModuleApi,
    private val billing: BillingModuleApi,
    private val retentionProperties: RetentionProperties,
    private val operators: OperatorModuleApi,
) {
    @Transactional(readOnly = true)
    fun dashboard(eventId: UUID): DashboardResponse {
        val event = events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        val guests = invitation.guestStats(eventId)
        val attendance = ticketing.attendanceStats(eventId)
        val countsByLabel = ticketing.checkInCountsByLabel(eventId)

        // Every entry point appears, including operators who haven't checked anyone
        // in yet, plus any label present in the counts (e.g. organizer-led).
        val labels = LinkedHashSet(operators.eventOperatorLabels(eventId))
        labels.addAll(countsByLabel.keys)
        val entrances = labels.map { EntranceCount(it, countsByLabel[it] ?: 0L) }
        val quorum = events.quorum(eventId, guests.total, attendance.checkedIn)
        val deliverability = notifications.currentTenantDeliverability()
        // Lecture sans effet de bord : le dashboard est pollé et ne doit jamais
        // écrire dans la ligne de facturation (contrairement à l'écran usage).
        val allowance = billing.readAllowance(eventId)

        return DashboardResponse(
            eventName = event.name,
            eventStatus = event.status,
            totalGuests = guests.total,
            invited = guests.invited,
            confirmed = guests.confirmed,
            declined = guests.declined,
            pending = guests.pending,
            checkedIn = attendance.checkedIn,
            entrances = entrances,
            quorum =
                quorum?.let {
                    QuorumView(
                        required = it.required,
                        current = it.current,
                        reached = it.reached,
                        reachedAt = it.reachedAt,
                    )
                },
            deliverability =
                DeliverabilityFlag(
                    bounceRatePercent = Math.round(deliverability.bounceRate * 100).toInt(),
                    warn = deliverability.warn,
                ),
            usage =
                UsageSummary(
                    invited = allowance.invitedGuests,
                    allowance = allowance.allowance,
                    remaining = allowance.remaining,
                    tier = allowance.tier,
                    withinAllowance = allowance.withinAllowance,
                    dataRetention = retentionNotice(event.endDateTime ?: event.startDateTime),
                ),
        )
    }

    /**
     * A notice for the organizer when an event's guest data is within the notice
     * window of its retention cutoff (JIKU-37), so anonymization is never a surprise.
     */
    private fun retentionNotice(eventDate: Instant?): DataRetentionNotice? {
        if (eventDate == null) return null
        val anonymizeOn = eventDate.plus(Duration.ofDays(retentionProperties.days))
        val noticeFrom = anonymizeOn.minus(Duration.ofDays(retentionProperties.noticeDays))
        return if (Instant.now().isAfter(noticeFrom)) DataRetentionNotice(anonymizeOn) else null
    }
}
