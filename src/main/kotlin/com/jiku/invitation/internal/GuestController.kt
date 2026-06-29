package com.jiku.invitation.internal

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/events/{eventId}/guests")
@PreAuthorize("hasRole('ORGANIZER_ADMIN')")
class GuestController(
    private val guestService: GuestService,
    private val guestExportService: GuestExportService,
) {
    @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun import(
        @PathVariable eventId: UUID,
        @RequestParam("file") file: MultipartFile,
    ): GuestImportResult = guestService.import(eventId, file)

    @GetMapping
    fun list(
        @PathVariable eventId: UUID,
    ): List<GuestResponse> = guestService.list(eventId)

    @GetMapping("/export", produces = ["text/csv"])
    fun export(
        @PathVariable eventId: UUID,
        response: HttpServletResponse,
    ) {
        response.contentType = "text/csv; charset=UTF-8"
        response.setHeader("Content-Disposition", "attachment; filename=\"guests-$eventId.csv\"")
        guestExportService.writeCsv(eventId, response.writer)
    }
}
