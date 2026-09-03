package com.jiku.checkin.internal

import com.jiku.shared.PdfDocument
import com.lowagie.text.Element
import com.lowagie.text.Paragraph
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Rend l'attestation individuelle et la feuille d'émargement (JIKU-95).
 *
 * Quand un bailleur ou un employeur finance une session, le versement du solde
 * est conditionné à la preuve de présence. Aujourd'hui la feuille circule sur
 * papier, des signatures manquent, et le cabinet passe une demi-journée à
 * reconstituer le dossier. Ces documents sont cette preuve, produite seule.
 *
 * Toutes les heures sont rendues dans le fuseau de **l'événement**, jamais celui
 * du serveur ni du lecteur : une formation à Conakry s'atteste à l'heure de
 * Conakry, quel que soit l'endroit d'où le document est téléchargé.
 */
@Component
class AttendanceDocumentRenderer {
    /** Attestation nominative, pour un participant présent. */
    fun certificate(data: CertificateData): ByteArray =
        PdfDocument.render { document ->
            document.add(
                Paragraph().apply {
                    add(Paragraph("Attestation de présence", PdfDocument.TITLE))
                    add(Paragraph(data.eventName, PdfDocument.SUBTITLE))
                    spacingAfter = 24f
                },
            )

            document.add(
                PdfDocument.table(floatArrayOf(1f, 1f), spacingAfter = 24f).apply {
                    addCell(PdfDocument.partyCell("Délivrée par", data.issuerLines))
                    addCell(PdfDocument.partyCell("Participant", listOf(data.participantName)))
                },
            )

            document.add(
                PdfDocument.table(floatArrayOf(1f, 2f), spacingAfter = 24f).apply {
                    addCell(PdfDocument.headerCell("Information"))
                    addCell(PdfDocument.headerCell("Valeur"))
                    addCell(PdfDocument.bodyCell("Événement"))
                    addCell(PdfDocument.bodyCell(data.eventName))
                    data.eventDate?.let {
                        addCell(PdfDocument.bodyCell("Date"))
                        addCell(PdfDocument.bodyCell(formatDate(it, data.timezone)))
                    }
                    addCell(PdfDocument.bodyCell("Heure d'arrivée"))
                    addCell(PdfDocument.bodyCell(formatDateTime(data.arrivedAt, data.timezone)))
                    addCell(PdfDocument.bodyCell("Poste de contrôle"))
                    addCell(PdfDocument.bodyCell(data.checkedInBy ?: "—"))
                },
            )

            document.add(
                Paragraph(
                    "Présence enregistrée électroniquement au moment de l'entrée. " +
                        "Heures exprimées dans le fuseau ${data.timezone}.",
                    PdfDocument.SMALL,
                ),
            )
        }

    /** Feuille d'émargement : tous les présents, horodatés, avec un total. */
    fun register(data: RegisterData): ByteArray =
        PdfDocument.render { document ->
            document.add(
                Paragraph().apply {
                    add(Paragraph("Feuille d'émargement", PdfDocument.TITLE))
                    add(Paragraph(data.eventName, PdfDocument.SUBTITLE))
                    data.eventDate?.let {
                        add(Paragraph(formatDate(it, data.timezone), PdfDocument.SMALL))
                    }
                    spacingAfter = 20f
                },
            )

            document.add(
                PdfDocument.table(floatArrayOf(1f), spacingAfter = 20f).apply {
                    addCell(PdfDocument.partyCell("Délivrée par", data.issuerLines))
                },
            )

            document.add(
                PdfDocument.table(floatArrayOf(4f, 3f, 3f)).apply {
                    listOf("Participant", "Heure d'arrivée", "Poste de contrôle")
                        .forEach { addCell(PdfDocument.headerCell(it)) }
                    data.entries.forEach { entry ->
                        addCell(PdfDocument.bodyCell(entry.name))
                        addCell(PdfDocument.bodyCell(formatDateTime(entry.arrivedAt, data.timezone)))
                        addCell(PdfDocument.bodyCell(entry.checkedInBy ?: "—"))
                    }
                },
            )

            document.add(
                PdfDocument.table(floatArrayOf(6f, 2f), spacingAfter = 16f).apply {
                    addCell(PdfDocument.totalCell("Total présents"))
                    addCell(PdfDocument.totalCell(data.entries.size.toString(), Element.ALIGN_RIGHT))
                },
            )

            document.add(
                Paragraph(
                    "Présences enregistrées électroniquement à l'entrée. " +
                        "Heures exprimées dans le fuseau ${data.timezone}.",
                    PdfDocument.SMALL,
                ),
            )
        }

    fun certificateFileName(data: CertificateData): String = "attestation-${slug(data.eventName)}-${slug(data.participantName)}.pdf"

    fun registerFileName(data: RegisterData): String = "emargement-${slug(data.eventName)}.pdf"

    private fun formatDateTime(
        instant: Instant,
        timezone: String,
    ): String = DATE_TIME.withZone(ZoneId.of(timezone)).format(instant)

    private fun formatDate(
        instant: Instant,
        timezone: String,
    ): String = DATE.withZone(ZoneId.of(timezone)).format(instant)

    private fun slug(value: String): String =
        value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "document" }

    private companion object {
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}

/** Un participant présent, tel que l'attestation le nomme. */
data class CertificateData(
    val eventName: String,
    val eventDate: Instant?,
    val timezone: String,
    val issuerLines: List<String>,
    val participantName: String,
    val arrivedAt: Instant,
    val checkedInBy: String?,
)

data class RegisterData(
    val eventName: String,
    val eventDate: Instant?,
    val timezone: String,
    val issuerLines: List<String>,
    val entries: List<RegisterEntry>,
)

data class RegisterEntry(
    val name: String,
    val arrivedAt: Instant,
    val checkedInBy: String?,
)
