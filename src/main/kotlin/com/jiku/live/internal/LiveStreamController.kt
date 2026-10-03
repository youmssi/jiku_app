package com.jiku.live.internal

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * The live stream (JIKU-214): `ready` once open, then `change` each time the
 * ticket's topic changed. No account: the ticket, issued by the screen's own
 * endpoint after its usual checks, is the credential.
 */
@RestController
@RequestMapping("/live")
class LiveStreamController(
    private val tickets: LiveTickets,
    private val hub: LiveHub,
) {
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @RequestParam ticket: String,
    ): ResponseEntity<SseEmitter> {
        val topic =
            tickets.topicOf(ticket) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "This live ticket is invalid or expired")
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
            .header("X-Accel-Buffering", "no")
            .body(hub.open(topic))
    }
}
