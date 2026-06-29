package com.jiku.ticketing.internal

import com.jiku.ticketing.AttendanceStats
import com.jiku.ticketing.CheckInOutcome
import com.jiku.ticketing.CheckInResult
import com.jiku.ticketing.TicketInfo
import com.jiku.ticketing.TicketingModuleApi
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
    ): TicketInfo {
        val existing = tickets.findByGuestId(guestId)
        if (existing != null) {
            if (existing.status == TicketStatus.CANCELLED) {
                existing.status = TicketStatus.ISSUED
                tickets.save(existing)
            }
            return existing.toInfo()
        }
        val ticket = Ticket(eventId = eventId, guestId = guestId, ticketCode = codeGenerator.generate())
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

    @Transactional(readOnly = true)
    override fun attendanceStats(eventId: UUID): AttendanceStats =
        AttendanceStats(
            checkedIn = tickets.countByEventIdAndStatus(eventId, TicketStatus.CHECKED_IN),
            confirmed = tickets.countByEventIdAndStatusIn(eventId, ACTIVE_STATUSES),
        )

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
        eventId = eventId,
        guestId = guestId,
        ticketCode = ticketCode,
        status = status.name,
        issuedAt = issuedAt,
        checkedInAt = checkedInAt,
        checkedInBy = checkedInBy,
    )
