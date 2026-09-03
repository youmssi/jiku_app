package com.jiku.checkin.internal

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Preuves de présence téléchargeables (JIKU-95). Tenant-scopé par le filtre de
 * persistance : un organisateur ne peut télécharger que ses propres documents.
 */
@RestController
@RequestMapping("/events/{eventId}/attendance")
@PreAuthorize("hasRole('ORGANIZER')")
class AttendanceCertificateController(
    private val service: AttendanceCertificateService,
) {
    @GetMapping("/register", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun register(
        @PathVariable eventId: UUID,
    ): ResponseEntity<ByteArray> = download(service.register(eventId))

    @GetMapping("/certificate/{guestId}", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun certificate(
        @PathVariable eventId: UUID,
        @PathVariable guestId: UUID,
    ): ResponseEntity<ByteArray> = download(service.certificate(eventId, guestId))

    private fun download(document: RenderedDocument): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${document.fileName}\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(document.bytes)
}
