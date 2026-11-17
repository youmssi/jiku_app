package com.jiku.money.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** The gifts the back office offered the organization, and their activation (JIKU-315). */
@RestController
@RequestMapping("/billing/gifts")
@PreAuthorize("hasRole('ORGANIZER')")
class GiftController(
    private val gifts: GiftService,
) {
    @GetMapping
    fun list(): List<GiftView> = gifts.list()

    @PostMapping("/{giftId}/activate")
    fun activate(
        @PathVariable giftId: UUID,
        @RequestBody(required = false) request: ActivateGiftRequest?,
    ): GiftView = gifts.activate(giftId, request?.eventId)
}

data class ActivateGiftRequest(
    /** The event to add the guests to, when the gift names none. */
    val eventId: UUID? = null,
)
