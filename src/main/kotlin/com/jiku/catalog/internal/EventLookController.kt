package com.jiku.catalog.internal

import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Duration
import java.util.UUID

/** The organizer picks the look of an event's guest-facing surfaces (JIKU-194). */
@RestController
@RequestMapping("/events/{eventId}")
@PreAuthorize("hasRole('ORGANIZER')")
class EventLookController(
    private val look: EventLookService,
) {
    @GetMapping("/look")
    fun get(
        @PathVariable eventId: UUID,
    ): EventLookView = look.look(eventId)

    @PutMapping("/look")
    fun update(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: EventLookRequest,
    ): EventLookView = look.update(eventId, request)

    @PutMapping("/banner", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun replaceBanner(
        @PathVariable eventId: UUID,
        @RequestPart("file") file: MultipartFile,
    ): EventLookView = look.replaceBanner(eventId, file.bytes, file.contentType)

    @DeleteMapping("/banner")
    fun removeBanner(
        @PathVariable eventId: UUID,
    ): EventLookView = look.removeBanner(eventId)
}

/** The banner photo as public pages, link previews and WhatsApp fetch it. */
@RestController
@RequestMapping("/public/events/{eventId}")
class PublicEventBannerController(
    private val look: EventLookService,
) {
    @GetMapping("/banner")
    fun banner(
        @PathVariable eventId: UUID,
    ): ResponseEntity<ByteArray> {
        val banner = look.publicBanner(eventId)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(banner.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .body(banner.content)
    }
}
