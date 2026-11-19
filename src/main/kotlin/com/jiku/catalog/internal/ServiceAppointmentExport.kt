package com.jiku.catalog.internal

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A service's appointments over a period as CSV (JIKU-191): when, who, the status,
 * and one column per question with what each client answered. Times are written
 * in the service's time zone. A cell a spreadsheet would read as a formula is
 * prefixed so a client's name can never run as one.
 */
@Service
class ServiceAppointmentExport(
    private val services: ServiceAdminService,
    private val reservations: ServiceReservationRepository,
    private val guidedQuestions: ServiceQuestionService,
) {
    @Transactional(readOnly = true)
    fun writeCsv(
        serviceId: UUID,
        from: LocalDate,
        to: LocalDate,
        appendable: Appendable,
    ) {
        if (to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The period runs at most $MAX_DAYS days")
        }
        val service = services.get(serviceId)
        val zone = ZoneId.of(service.timezone)
        val bookings =
            reservations
                .findBookingsBetween(serviceId, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
                .distinctBy { it.bookingTokenHash }
        val answers = guidedQuestions.answersOf(bookings.mapNotNull { it.bookingTokenHash })
        val questions =
            answers.values
                .flatten()
                .map { it.question }
                .distinct()
        val format =
            CSVFormat.DEFAULT
                .builder()
                .setHeader(*(HEADERS + questions).toTypedArray())
                .get()
        CSVPrinter(appendable, format).use { printer ->
            for (booking in bookings) {
                val given = answers[booking.bookingTokenHash].orEmpty().associate { it.question to it.answer }
                val local = booking.startsAt.atZone(zone)
                printer.printRecord(
                    listOf(
                        local.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        local.format(TIME),
                        safe(booking.clientName),
                        safe(booking.clientPhone),
                        booking.status.name,
                    ) + questions.map { safe(given[it]) },
                )
            }
        }
    }

    private fun safe(value: String?): String {
        val text = value.orEmpty()
        // A phone number like +224… is data, not a formula: only other leading signs are neutralized.
        if (PHONE.matches(text)) return text
        return if (text.isNotEmpty() && text.first() in FORMULA_STARTS) "'$text" else text
    }

    private companion object {
        const val MAX_DAYS = 92L
        val HEADERS = listOf("Date", "Heure", "Client", "Téléphone", "Statut")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
        val PHONE = Regex("^\\+?[0-9 ]{6,20}$")
    }
}
