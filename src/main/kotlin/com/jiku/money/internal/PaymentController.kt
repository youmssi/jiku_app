package com.jiku.money.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Organizer-facing payment initiation for an event's guests (JIKU-33, JIKU-268). Tenant
 * is bound from the JWT; the payment is confirmed later by the provider callback,
 * not here.
 */
@RestController
@RequestMapping("/events/{eventId}/payments")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class PaymentController(
    private val paymentService: PaymentService,
) {
    @PostMapping
    fun initiate(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: EventGuestsRequest,
    ): PaymentInitiationResult = paymentService.initiate(eventId, requireNotNull(request.guests))
}

/** The paid guests an event should reach (JIKU-268). */
data class EventGuestsRequest(
    @field:NotNull
    @field:Min(1)
    val guests: Long?,
)
