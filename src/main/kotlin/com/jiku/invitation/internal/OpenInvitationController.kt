package com.jiku.invitation.internal

import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Duration
import java.util.UUID

/**
 * The organization's side of an event's open invitation (JIKU-184): its
 * settings and counts, and the people who answered.
 */
@RestController
@RequestMapping("/events/{eventId}/open-invitation")
@PreAuthorize("hasRole('ORGANIZER')")
class OpenInvitationController(
    private val service: OpenInvitationService,
    private val funnel: CardFunnelService,
    private val voice: CardVoiceService,
) {
    /** Records or replaces the host's voice message (JIKU-284): the file, its length in seconds and its words. */
    @PutMapping("/voice", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun replaceVoice(
        @PathVariable eventId: UUID,
        @RequestParam file: MultipartFile,
        @RequestParam durationSeconds: Int,
        @RequestParam(required = false) transcript: String?,
    ): CardVoiceView = voice.replace(eventId, file.bytes, durationSeconds, transcript)

    @DeleteMapping("/voice")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeVoice(
        @PathVariable eventId: UUID,
    ) = voice.remove(eventId)

    /** Shares, visits and answers per network (JIKU-282). */
    @GetMapping("/funnel")
    fun funnel(
        @PathVariable eventId: UUID,
    ): List<CardFunnelRow> = funnel.funnel(eventId)

    /** The organizer shared the card through [source]; counted, never tied to anyone. */
    @PostMapping("/shares")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun share(
        @PathVariable eventId: UUID,
        @RequestParam source: String,
    ) = funnel.recordShare(eventId, CardSource.of(source))

    /** 204 while the event has no open invitation yet. */
    @GetMapping
    fun settings(
        @PathVariable eventId: UUID,
    ): ResponseEntity<OpenInvitationView> = service.settings(eventId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    /** Opens the invitation the first time, then changes its settings. */
    @PutMapping
    fun update(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: OpenInvitationSettingsRequest,
    ): OpenInvitationView = service.update(eventId, request)

    @GetMapping("/responses")
    fun responses(
        @PathVariable eventId: UUID,
    ): List<OrganizerOpenResponseView> = service.responses(eventId)

    @DeleteMapping("/responses/{responseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @PathVariable eventId: UUID,
        @PathVariable responseId: UUID,
    ) = service.remove(eventId, responseId)
}

/**
 * The public side (JIKU-184), without an account: a shared card's page and the
 * answer, found by the invitation's code. The code binds the tenant it belongs
 * to before the transactional service runs.
 */
@RestController
@RequestMapping("/open/{code}")
class PublicOpenInvitationController(
    private val service: OpenInvitationService,
    private val funnel: CardFunnelService,
    private val voice: CardVoiceService,
) {
    /** The host's voice message, fetched when a guest taps play; its address changes with each new recording. */
    @GetMapping("/voice")
    fun voice(
        @PathVariable code: String,
    ): ResponseEntity<ByteArray> {
        val recording = service.route(code) { eventId -> voice.recording(eventId) }
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(recording.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .body(recording.content)
    }

    /** The card page was opened from a link tagged [source] (JIKU-282). Sent by the page's script, so link previews never count. */
    @PostMapping("/visits")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun visit(
        @PathVariable code: String,
        @RequestParam(required = false) source: String?,
    ) = service.route(code) { eventId -> funnel.recordVisit(eventId, CardSource.of(source)) }

    @GetMapping
    fun view(
        @PathVariable code: String,
    ): PublicOpenInvitationView = service.route(code) { eventId -> service.publicView(eventId) }

    @PostMapping("/responses")
    fun respond(
        @PathVariable code: String,
        @Valid @RequestBody request: OpenResponseRequest,
    ): OpenResponseView = service.route(code) { eventId -> service.respond(eventId, request, OpenResponseChannel.WEB) }
}
