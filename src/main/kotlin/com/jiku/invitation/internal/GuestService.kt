package com.jiku.invitation.internal

import com.jiku.event.EventModuleApi
import com.jiku.notification.NotificationModuleApi
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
    private val events: EventModuleApi,
    private val properties: GuestImportProperties,
    private val notifications: NotificationModuleApi,
) {
    @Transactional(readOnly = true)
    fun list(eventId: UUID): List<GuestResponse> =
        guests.findByEventId(eventId).map {
            GuestResponse(requireNotNull(it.id), it.firstName, it.lastName, it.email, it.phoneNumber)
        }

    @Transactional
    fun import(
        eventId: UUID,
        file: MultipartFile,
    ): GuestImportResult {
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

        val failures = mutableListOf<RowIssue>()
        val warnings = mutableListOf<RowIssue>()
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

                guests.save(
                    Guest(
                        eventId = eventId,
                        firstName = requireNotNull(firstName),
                        lastName = requireNotNull(lastName),
                        email = email,
                        phoneNumber = phone,
                    ),
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
