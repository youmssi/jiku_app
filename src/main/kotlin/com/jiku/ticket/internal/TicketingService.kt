package com.jiku.ticket.internal

import com.jiku.ticket.AttendanceStats
import com.jiku.ticket.CheckInOutcome
import com.jiku.ticket.CheckInResult
import com.jiku.ticket.TicketInfo
import com.jiku.ticket.TicketingModuleApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class TicketingService(
    private val tickets: TicketRepository,
    private val codeGenerator: TicketCodeGenerator,
) : TicketingModuleApi {
    @Transactional
    override fun issueTicket(
        eventId: UUID,
        guestId: UUID,
        ticketTypeId: UUID?,
    ): TicketInfo {
        val existing = tickets.findByGuestId(guestId)
        if (existing != null) {
            if (existing.status == TicketStatus.CANCELLED) {
                existing.status = TicketStatus.ISSUED
                tickets.save(existing)
            }
            return existing.toInfo()
        }
        val ticket =
            Ticket(eventId = eventId, guestId = guestId, ticketCode = codeGenerator.generate())
                .apply { this.ticketTypeId = ticketTypeId }
        tickets.save(ticket)
        return ticket.toInfo()
    }

    @Transactional
    override fun cancelByGuest(guestId: UUID) {
        tickets.findByGuestId(guestId)?.let {
            it.status = TicketStatus.CANCELLED
            tickets.save(it)
        }
    }

    @Transactional
    override fun issueAppointment(
        guestId: UUID,
        startsAt: Instant,
        endsAt: Instant,
        serviceId: UUID,
        professionalName: String?,
    ): String {
        val ticket =
            Ticket(eventId = null, guestId = guestId, ticketCode = codeGenerator.generate()).apply {
                kind = TicketKind.APPOINTMENT
                this.startsAt = startsAt
                this.endsAt = endsAt
                this.serviceId = serviceId
                this.professionalName = professionalName
            }
        tickets.save(ticket)
        return ticket.ticketCode
    }

    @Transactional(readOnly = true)
    override fun findByGuest(guestId: UUID): TicketInfo? = tickets.findByGuestId(guestId)?.toInfo()

    @Transactional(readOnly = true)
    override fun findByCode(ticketCode: String): TicketInfo? = tickets.findByTicketCode(ticketCode)?.toInfo()

    @Transactional
    override fun checkInByCode(
        ticketCode: String,
        checkedInBy: String,
    ): CheckInResult = checkIn(tickets.findByTicketCode(ticketCode), checkedInBy)

    @Transactional
    override fun checkInByGuest(
        guestId: UUID,
        checkedInBy: String,
    ): CheckInResult = checkIn(tickets.findByGuestId(guestId), checkedInBy)

    @Transactional
    override fun syncCheckInByCode(
        ticketCode: String,
        checkedInBy: String,
        scannedAt: Instant,
    ): CheckInResult {
        val ticket = tickets.findByTicketCode(ticketCode) ?: return CheckInResult(CheckInOutcome.NOT_FOUND)
        val ticketId = requireNotNull(ticket.id)
        // Claim the check-in with the device's scan time as the recorded moment.
        if (tickets.checkIn(ticketId, scannedAt, checkedInBy) == 1) {
            return CheckInResult(
                outcome = CheckInOutcome.CHECKED_IN,
                ticket = tickets.findById(ticketId).get().toInfo(),
                checkedInAt = scannedAt,
                checkedInBy = checkedInBy,
            )
        }
        val current = tickets.findById(ticketId).get()
        if (current.status == TicketStatus.CANCELLED) {
            return CheckInResult(CheckInOutcome.CANCELLED, current.toInfo())
        }
        // Already checked in: the earliest scan owns the record (first-timestamp-wins).
        if (tickets.reassignEarlierCheckIn(ticketId, scannedAt, checkedInBy) == 1) {
            val owned = tickets.findById(ticketId).get()
            return CheckInResult(CheckInOutcome.CHECKED_IN, owned.toInfo(), owned.checkedInAt, owned.checkedInBy)
        }
        return CheckInResult(
            CheckInOutcome.ALREADY_CHECKED_IN,
            current.toInfo(),
            current.checkedInAt,
            current.checkedInBy,
        )
    }

    @Transactional(readOnly = true)
    override fun attendanceStats(eventId: UUID): AttendanceStats =
        AttendanceStats(
            checkedIn = tickets.countByEventIdAndStatus(eventId, TicketStatus.CHECKED_IN),
            confirmed = tickets.countByEventIdAndStatusIn(eventId, ACTIVE_STATUSES),
        )

    @Transactional(readOnly = true)
    override fun findTicketsByEvent(eventId: UUID): List<TicketInfo> = tickets.findByEventId(eventId).map { it.toInfo() }

    @Transactional(readOnly = true)
    override fun checkInCountsByLabel(eventId: UUID): Map<String, Long> =
        tickets.checkInCountsByLabel(eventId).associate { row -> (row[0] as String) to (row[1] as Long) }

    private fun checkIn(
        ticket: Ticket?,
        checkedInBy: String,
    ): CheckInResult {
        if (ticket == null) return CheckInResult(CheckInOutcome.NOT_FOUND)
        val ticketId = requireNotNull(ticket.id)
        val now = Instant.now()
        if (tickets.checkIn(ticketId, now, checkedInBy) == 1) {
            return CheckInResult(
                outcome = CheckInOutcome.CHECKED_IN,
                ticket = tickets.findById(ticketId).get().toInfo(),
                checkedInAt = now,
                checkedInBy = checkedInBy,
            )
        }
        // The conditional update matched no row: re-read to report why precisely.
        val current = tickets.findById(ticketId).get()
        val outcome =
            when (current.status) {
                TicketStatus.CHECKED_IN -> CheckInOutcome.ALREADY_CHECKED_IN
                TicketStatus.CANCELLED -> CheckInOutcome.CANCELLED
                TicketStatus.ISSUED -> CheckInOutcome.NOT_FOUND
            }
        return CheckInResult(
            outcome = outcome,
            ticket = current.toInfo(),
            checkedInAt = current.checkedInAt,
            checkedInBy = current.checkedInBy,
        )
    }

    private companion object {
        val ACTIVE_STATUSES = listOf(TicketStatus.ISSUED, TicketStatus.CHECKED_IN)
    }
}

private fun Ticket.toInfo(): TicketInfo =
    TicketInfo(
        id = requireNotNull(id),
        eventId = requireNotNull(eventId),
        guestId = guestId,
        ticketCode = ticketCode,
        status = status.name,
        issuedAt = issuedAt,
        checkedInAt = checkedInAt,
        checkedInBy = checkedInBy,
        ticketTypeId = ticketTypeId,
    )
