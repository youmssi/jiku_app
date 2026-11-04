package com.jiku.catalog.internal

import com.jiku.shared.MessageLanguage
import com.jiku.shared.TenantLanguage
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The language an event's guests read (JIKU-293): the card, the ticket, the
 * links they open and the WhatsApp and email messages they get. Until the
 * organizer picks one, it is the organization's.
 */
@RestController
@RequestMapping("/events/{eventId}/guest-language")
@PreAuthorize("hasRole('ORGANIZER')")
class EventGuestLanguageController(
    private val service: EventGuestLanguageService,
) {
    @GetMapping
    fun get(
        @PathVariable eventId: UUID,
    ): GuestLanguageView = service.view(eventId)

    @PutMapping
    fun update(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: GuestLanguageRequest,
    ): GuestLanguageView = service.update(eventId, request.language)
}

data class GuestLanguageRequest(
    /** fr, en or ar; null goes back to the organization's language. */
    @field:Pattern(regexp = "fr|en|ar")
    val language: String? = null,
)

data class GuestLanguageView(
    /** The language the guests read: the chosen one, else the organization's. */
    val language: String,
    /** Whether the organizer chose it on this event. */
    val chosen: Boolean,
)

@Service
class EventGuestLanguageService(
    private val events: EventRepository,
    private val tenantLanguage: TenantLanguage,
) {
    @Transactional(readOnly = true)
    fun view(eventId: UUID): GuestLanguageView = load(eventId).toView()

    @Transactional
    fun update(
        eventId: UUID,
        language: String?,
    ): GuestLanguageView {
        val event = load(eventId)
        event.guestLanguage = language?.takeIf { it in MessageLanguage.GUEST }
        return events.save(event).toView()
    }

    private fun Event.toView(): GuestLanguageView =
        GuestLanguageView(
            language = guestLanguage ?: tenantLanguage.of(requireNotNull(tenantId)),
            chosen = guestLanguage != null,
        )

    private fun load(eventId: UUID): Event =
        events.findById(eventId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found") }
}
