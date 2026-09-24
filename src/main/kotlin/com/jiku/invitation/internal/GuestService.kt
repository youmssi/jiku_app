package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.messaging.NotificationModuleApi
import com.jiku.ticket.TicketInfo
import com.jiku.ticket.TicketingModuleApi
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Guest import and listing. CSV rows are validated independently so one bad row
 * never fails the whole batch; the result reports per-row failures, the number of
 * duplicates skipped, and deliverability warnings the organizer can review.
 */
@Service
class GuestService(
    private val guests: GuestRepository,
    private val invitations: InvitationRepository,
    private val events: EventModuleApi,
    private val properties: GuestImportProperties,
    private val notifications: NotificationModuleApi,
    private val ticketing: TicketingModuleApi,
) {
    @Transactional(readOnly = true)
    fun list(eventId: UUID): List<GuestResponse> {
        // Une seule lecture des tickets pour toute la liste : la table d'invités
        // se recharge à chaque filtre, et une requête par ligne s'y verrait.
        // A cancelled ticket (declined, transferred) no longer belongs to anyone.
        val ticketByGuest =
            ticketing
                .findTicketsByEvent(eventId)
                .filter { it.status != TicketInfo.STATUS_CANCELLED }
                .associateBy { it.guestId }
        return guests.findByEventId(eventId).map { it.toResponse(ticketByGuest[it.id]) }
    }

    /**
     * Removes a guest who has never been invited (added by mistake, duplicate entry,
     * etc.). Once any invitation has been queued or sent, the guest may carry a
     * ticket or check-in record in other modules, so removal is refused in favor of
     * [setExcluded] — the roster entry stays, but no further invitation is sent.
     */
    @Transactional
    fun remove(
        eventId: UUID,
        guestId: UUID,
    ) {
        val guest =
            guests.findByIdAndEventId(guestId, eventId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Guest not found")
        val everInvited = invitations.findByGuestId(guestId).any { it.status != InvitationStatus.FAILED }
        if (everInvited) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "This guest has already been invited and can no longer be removed. " +
                    "Exclude them instead to stop future invitations.",
            )
        }
        invitations.deleteByGuestId(guestId)
        guests.delete(guest)
    }

    @Transactional
    fun setExcluded(
        eventId: UUID,
        guestId: UUID,
        excluded: Boolean,
    ): GuestResponse {
        val guest =
            guests.findByIdAndEventId(guestId, eventId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Guest not found")
        guest.excludedFromInvitations = excluded
        return guests.save(guest).withLiveTicket()
    }

    /**
     * Rattache un invité à une catégorie d'accès (JIKU-93).
     *
     * Si l'invité a déjà confirmé, sa place suit : elle quitte l'ancienne catégorie
     * pour la nouvelle. Le compteur global ne bouge pas — la personne était déjà
     * comptée dans la salle. Si la catégorie visée est pleine, on refuse plutôt que
     * de la faire déborder.
     */
    @Transactional
    fun setTicketType(
        eventId: UUID,
        guestId: UUID,
        ticketTypeId: UUID?,
    ): GuestResponse {
        val guest =
            guests.findByIdAndEventId(guestId, eventId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Guest not found")
        if (ticketTypeId != null && events.ticketTypes(eventId).none { it.id == ticketTypeId }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category for this event")
        }
        if (guest.ticketTypeId == ticketTypeId) return guest.withLiveTicket()
        if (guest.rsvpStatus == RsvpStatus.CONFIRMED &&
            !events.moveTicketTypeSlot(guest.ticketTypeId, ticketTypeId)
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This category is full")
        }
        guest.ticketTypeId = ticketTypeId
        return guests.save(guest).withLiveTicket()
    }

    private fun Guest.withLiveTicket(): GuestResponse =
        toResponse(ticketing.findByGuest(requireNotNull(id))?.takeIf { it.status != TicketInfo.STATUS_CANCELLED })

    private fun Guest.toResponse(ticket: TicketInfo?) =
        GuestResponse(
            id = requireNotNull(id),
            firstName = firstName,
            lastName = lastName,
            email = email,
            phoneNumber = phoneNumber,
            excludedFromInvitations = excludedFromInvitations,
            checkedInAt = ticket?.takeIf { it.status == TicketInfo.STATUS_CHECKED_IN }?.checkedInAt,
            ticketTypeId = ticketTypeId,
            rsvpStatus = rsvpStatus,
            ticketCode = ticket?.ticketCode,
            paymentStatus = ticket?.paymentStatus,
            amountDueMinor = ticket?.amountDueMinor,
            amountDueCurrency = ticket?.amountDueCurrency,
        )

    @Transactional
    fun import(
        eventId: UUID,
        file: MultipartFile,
    ): GuestImportResult {
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

        val failures = mutableListOf<RowIssue>()
        val warnings = mutableListOf<RowIssue>()
        // Colonne `type` facultative (JIKU-93) : rapprochée par libellé, insensible
        // à la casse, parce que l'organisateur retape « vip » à la main.
        val typeIdByLabel = events.ticketTypes(eventId).associate { it.label.lowercase() to it.id }
        val seenEmails = mutableSetOf<String>()
        val seenPhones = mutableSetOf<String>()
        var imported = 0
        var duplicates = 0

        val format =
            CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreSurroundingSpaces(true)
                .get()

        file.inputStream.bufferedReader().use { reader ->
            val parser = format.parse(reader)
            val indexByNormalizedHeader = parser.headerMap.entries.associate { normalize(it.key) to it.value }
            val missing = REQUIRED_COLUMNS.filter { it !in indexByNormalizedHeader.keys }
            if (missing.isNotEmpty()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing required columns: ${missing.joinToString()}",
                )
            }

            fun field(
                record: CSVRecord,
                normalizedHeader: String,
            ): String? {
                val index = indexByNormalizedHeader[normalizedHeader] ?: return null
                return record.get(index).trim().ifBlank { null }
            }

            var rowNumber = 1 // the header is row 1
            for (record in parser) {
                rowNumber++
                if (imported + duplicates + failures.size >= properties.maxRows) {
                    throw ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "The file exceeds the ${properties.maxRows}-row limit.",
                    )
                }

                val firstName = field(record, "firstname")
                val lastName = field(record, "lastname")
                val email = field(record, "email")?.lowercase()
                val phone = field(record, "phone")
                val typeLabel = field(record, "type")

                val problem = validateRow(firstName, lastName, email, phone)
                if (problem != null) {
                    failures += RowIssue(rowNumber, problem)
                    continue
                }
                if (isDuplicate(eventId, email, phone, seenEmails, seenPhones)) {
                    duplicates++
                    continue
                }
                email?.let { seenEmails += it }
                phone?.let { seenPhones += it }
                disposableWarning(email)?.let { warnings += RowIssue(rowNumber, it) }
                if (email != null && notifications.isUndeliverable(email)) {
                    warnings += RowIssue(rowNumber, "Email previously bounced and may be undeliverable")
                }

                // Un libellé inconnu ne fait pas échouer la ligne : perdre l'invité
                // coûte plus cher que le rattacher plus tard. On le signale.
                val typeId = typeLabel?.let { typeIdByLabel[it.lowercase()] }
                if (typeLabel != null && typeId == null) {
                    warnings += RowIssue(rowNumber, "Unknown category « $typeLabel »: guest imported without a category")
                }

                guests.save(
                    Guest(
                        eventId = eventId,
                        firstName = requireNotNull(firstName),
                        lastName = requireNotNull(lastName),
                        email = email,
                        phoneNumber = phone,
                    ).apply { ticketTypeId = typeId },
                )
                imported++
            }
        }

        return GuestImportResult(imported, duplicates, failures.size, failures, warnings)
    }

    private fun validateRow(
        firstName: String?,
        lastName: String?,
        email: String?,
        phone: String?,
    ): String? {
        if (firstName == null) return "Missing first name"
        if (lastName == null) return "Missing last name"
        if (email == null && phone == null) return "Missing both email and phone"
        if (email != null && !EMAIL_REGEX.matches(email)) return "Invalid email format"
        if (phone != null && !PHONE_REGEX.matches(phone)) return "Invalid phone number"
        return null
    }

    private fun isDuplicate(
        eventId: UUID,
        email: String?,
        phone: String?,
        seenEmails: Set<String>,
        seenPhones: Set<String>,
    ): Boolean {
        if (email != null && (email in seenEmails || guests.existsByEventIdAndEmailIgnoreCase(eventId, email))) {
            return true
        }
        if (phone != null && (phone in seenPhones || guests.existsByEventIdAndPhoneNumber(eventId, phone))) {
            return true
        }
        return false
    }

    private fun disposableWarning(email: String?): String? {
        if (email == null) return null
        val domain = email.substringAfterLast('@')
        return if (domain in properties.disposableEmailDomains) "Email uses a disposable domain" else null
    }

    private fun normalize(header: String): String = header.lowercase().replace(NON_ALPHANUMERIC, "")

    private companion object {
        val REQUIRED_COLUMNS = listOf("firstname", "lastname", "email", "phone")
        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val PHONE_REGEX = Regex("^\\+?[0-9 ]{6,20}$")
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]")
    }
}
