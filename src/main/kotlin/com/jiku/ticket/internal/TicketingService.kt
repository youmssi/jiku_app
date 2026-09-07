package com.jiku.ticket.internal

import com.jiku.ticket.AttendanceStats
import com.jiku.ticket.CheckInOutcome
import com.jiku.ticket.CheckInResult
import com.jiku.ticket.LineActionResult
import com.jiku.ticket.LineOutcome
import com.jiku.ticket.LineTicket
import com.jiku.ticket.TicketInfo
import com.jiku.ticket.TicketingModuleApi
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class TicketingService(
    private val tickets: TicketRepository,
    private val codeGenerator: TicketCodeGenerator,
    private val rankAllocator: TicketDayRankAllocator,
    private val rankCounters: TicketDayCounterRepository,
) : TicketingModuleApi {
    private val log = LoggerFactory.getLogger(TicketingService::class.java)

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
        clientName: String,
        clientPhone: String,
    ): String {
        val ticket =
            Ticket(eventId = null, guestId = guestId, ticketCode = codeGenerator.generate()).apply {
                kind = TicketKind.APPOINTMENT
                this.startsAt = startsAt
                this.endsAt = endsAt
                this.serviceId = serviceId
                this.professionalName = professionalName
                this.clientName = clientName
                this.clientPhone = clientPhone
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

    @Transactional(readOnly = true)
    override fun serviceLine(
        serviceId: UUID,
        dayStart: Instant,
        dayEnd: Instant,
    ): List<LineTicket> {
        val entries = tickets.findServiceDay(serviceId, LINE_KINDS, dayStart, dayEnd)
        return entries.map { it.toLine() }
    }

    @Transactional
    override fun callNext(
        serviceId: UUID,
        dayStart: Instant,
        dayEnd: Instant,
        now: Instant,
        toleranceMinutes: Long,
    ): LineTicket? {
        // Réclame atomiquement la personne choisie par la règle. Si un autre
        // appareil l'a prise entre la lecture et l'écriture (0 ligne mise à jour),
        // on resélectionne — jamais deux appareils n'appellent la même personne.
        repeat(MAX_CLAIM_ATTEMPTS) {
            val entries = tickets.findServiceDay(serviceId, LINE_KINDS, dayStart, dayEnd)
            val byId = entries.associateBy { requireNotNull(it.id) }
            val candidates = entries.map { it.toCandidate() }
            val next =
                DayLinePolicy.nextToCall(candidates, now, toleranceMinutes)
                    ?: return null
            val claimed = byId.getValue(next.ticketId)
            // La transition a vidé le contexte : on relit pour rendre la personne
            // telle qu'elle est maintenant (APPELÉ), pas la photo prise avant.
            if (tickets.callLine(requireNotNull(claimed.id), serviceId) == 1) {
                return tickets.findById(requireNotNull(claimed.id)).orElse(claimed).toLine()
            }
        }
        return null
    }

    @Transactional
    override fun arriveByCode(
        serviceId: UUID,
        ticketCode: String,
        at: Instant,
        dayStart: Instant,
        dayEnd: Instant,
        rankDay: LocalDate,
    ): LineActionResult {
        val ticket =
            tickets.findByTicketCode(ticketCode)
                ?: return LineActionResult(LineOutcome.NOT_FOUND)
        if (!ticket.belongsToLineOf(serviceId)) {
            return LineActionResult(LineOutcome.WRONG_STATE, ticket.toLine())
        }
        // Le rendez-vous appartient à la journée : il y reçoit son rang.
        val startsAt = ticket.startsAt
        if (startsAt == null || startsAt < dayStart || startsAt >= dayEnd) {
            return LineActionResult(LineOutcome.WRONG_STATE, ticket.toLine())
        }
        // Déjà en attente (double scan) : pas de rang consommé pour rien.
        if (ticket.status != TicketStatus.ISSUED) {
            return LineActionResult(LineOutcome.WRONG_STATE, ticket.toLine())
        }
        // Transition d'abord, rang ensuite : si deux appareils scannent le même
        // billet, le perdant (0 ligne) repart sans avoir consommé de numéro, et la
        // numérotation du jour reste sans trou.
        val ticketId = requireNotNull(ticket.id)
        if (tickets.startWait(ticketId, serviceId, at) == 1) {
            tickets.assignDayRank(ticketId, allocateDayRank(serviceId, rankDay))
            return LineActionResult(LineOutcome.OK, reload(serviceId, ticketCode))
        }
        return LineActionResult(LineOutcome.WRONG_STATE, reload(serviceId, ticketCode))
    }

    @Transactional
    override fun callByCode(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult = lineStep(serviceId, ticketCode) { id -> tickets.callLine(id, serviceId) }

    @Transactional
    override fun presentByCode(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult = lineStep(serviceId, ticketCode) { id -> tickets.presentLine(id, serviceId) }

    @Transactional
    override fun finishByCode(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult = lineStep(serviceId, ticketCode) { id -> tickets.finishLine(id, serviceId) }

    @Transactional
    override fun noShowByCode(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult = lineStep(serviceId, ticketCode) { id -> tickets.noShowLine(id, serviceId) }

    @Transactional
    override fun issueWalkIn(
        guestId: UUID,
        serviceId: UUID,
        clientName: String,
        clientPhone: String,
        professionalName: String?,
        arrivedAt: Instant,
        dayStart: Instant,
        dayEnd: Instant,
        rankDay: LocalDate,
    ): LineTicket {
        require(arrivedAt >= dayStart && arrivedAt < dayEnd) { "Walk-in arrival falls outside its service day" }
        val rank = allocateDayRank(serviceId, rankDay)
        val ticket =
            Ticket(eventId = null, guestId = guestId, ticketCode = codeGenerator.generate()).apply {
                kind = TicketKind.WALK_IN
                this.serviceId = serviceId
                this.professionalName = professionalName
                this.clientName = clientName
                this.clientPhone = clientPhone
                status = TicketStatus.WAITING
                this.arrivedAt = arrivedAt
                dayRank = rank
            }
        tickets.save(ticket)
        return ticket.toLine()
    }

    /**
     * Alloue le prochain rang du jour de (service, [day]), séquentiel et unique.
     * La ligne de compteur est créée en propre transaction (une course perdue
     * aborterait la transaction de l'arrivée, qui a encore un billet à écrire),
     * puis l'incrément se fait sous verrou pessimiste : deux arrivées simultanées
     * obtiennent des rangs distincts, et un repli rend le rang.
     */
    private fun allocateDayRank(
        serviceId: UUID,
        day: LocalDate,
    ): Int {
        try {
            rankAllocator.ensureCounterExists(serviceId, day)
        } catch (ex: DataIntegrityViolationException) {
            log.debug("Day-rank counter for {} on {} was created concurrently", serviceId, day, ex)
        }
        val counter =
            rankCounters.findForUpdate(serviceId, day)
                ?: throw IllegalStateException("Day-rank counter for $serviceId on $day was not created")
        val rank = counter.nextRank
        counter.nextRank = rank + 1
        rankCounters.save(counter)
        return rank
    }

    private fun lineStep(
        serviceId: UUID,
        ticketCode: String,
        step: (UUID) -> Int,
    ): LineActionResult {
        val ticket =
            tickets.findByTicketCode(ticketCode)
                ?: return LineActionResult(LineOutcome.NOT_FOUND)
        if (!ticket.belongsToLineOf(serviceId)) {
            return LineActionResult(LineOutcome.WRONG_STATE, ticket.toLine())
        }
        val updated = step(requireNotNull(ticket.id)) == 1
        return LineActionResult(
            outcome = if (updated) LineOutcome.OK else LineOutcome.WRONG_STATE,
            ticket = reload(serviceId, ticketCode),
        )
    }

    /** Relecture d'un billet de la ligne, bornée au service — jamais un billet d'un autre service. */
    private fun reload(
        serviceId: UUID,
        ticketCode: String,
    ): LineTicket? {
        val ticket = tickets.findByTicketCode(ticketCode)
        return ticket?.takeIf { it.belongsToLineOf(serviceId) }?.toLine()
    }

    private fun Ticket.belongsToLineOf(serviceId: UUID): Boolean = kind in LINE_KINDS && this.serviceId == serviceId

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
                else -> CheckInOutcome.NOT_FOUND
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
        val LINE_KINDS = setOf(TicketKind.APPOINTMENT, TicketKind.WALK_IN)
        const val MAX_CLAIM_ATTEMPTS = 5
    }
}

private fun Ticket.toLine(): LineTicket =
    LineTicket(
        id = requireNotNull(id),
        ticketCode = ticketCode,
        kind = kind.name,
        status = status.name,
        clientName = clientName,
        clientPhone = clientPhone,
        startsAt = startsAt,
        endsAt = endsAt,
        arrivedAt = arrivedAt,
        dayRank = dayRank,
    )

private fun Ticket.toCandidate(): LineCandidate =
    LineCandidate(
        ticketId = requireNotNull(id),
        kind = kind,
        status = status,
        startsAt = startsAt,
        arrivedAt = arrivedAt,
    )

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
