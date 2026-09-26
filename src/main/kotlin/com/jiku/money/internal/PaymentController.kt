package com.jiku.money.internal

import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Organizer-facing payment initiation for an event's usage tier (JIKU-33). Tenant
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
        @RequestBody request: InitiatePaymentRequest,
    ): PaymentInitiationResult =
        try {
            paymentService.initiate(eventId, request.tier)
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message, ex)
        }
}

data class InitiatePaymentRequest(
    @field:NotBlank
    val tier: String,
)
