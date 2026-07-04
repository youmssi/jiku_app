package com.jiku.checkin.internal

import com.jiku.billing.BillingModuleApi
import com.jiku.event.EventModuleApi
import com.jiku.invitation.InvitationModuleApi
import com.jiku.notification.NotificationModuleApi
import com.jiku.ticketing.TicketingModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Aggregates an event's live dashboard metrics from the modules that own them:
 * guest/RSVP counts from invitation, attendance from ticketing, and the
 * per-entrance check-in breakdown from validator labels. Read-only; safe to poll.
 */
@Service
class DashboardService(
    private val events: EventModuleApi,
    private val invitation: InvitationModuleApi,
    private val ticketing: TicketingModuleApi,
    private val notifications: NotificationModuleApi,
    private val billing: BillingModuleApi,
    private val validators: ValidatorRepository,
) {
    @Transactional(readOnly = true)
    fun dashboard(eventId: UUID): DashboardResponse {
        val event = events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        val guests = invitation.guestStats(eventId)
        val attendance = ticketing.attendanceStats(eventId)
        val countsByLabel = ticketing.checkInCountsByLabel(eventId)

        // Every entry point appears, including validator links that haven't checked
        // anyone in yet, plus any label present in the counts (e.g. organizer-led).
        val labels = LinkedHashSet<String>()
        validators.findByEventIdOrderByCreatedAtAsc(eventId).forEach { labels.add(it.label) }
        labels.addAll(countsByLabel.keys)
        val entrances = labels.map { EntranceCount(it, countsByLabel[it] ?: 0L) }
        val deliverability = notifications.currentTenantDeliverability()
        val allowance = billing.allowance(eventId)

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
                ),
        )
    }
}
