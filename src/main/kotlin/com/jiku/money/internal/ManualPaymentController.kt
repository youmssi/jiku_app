package com.jiku.money.internal

import com.jiku.money.ManualPaymentInstructions
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Organizer-facing manual activation requests (JIKU-41). POST records the request
 * (idempotently) and returns the payment instructions; GET re-reads the open
 * request so the instructions are never a one-shot screen. Confirmation is an
 * admin action, never anything the client's browser can trigger.
 */
@RestController
@RequestMapping("/events/{eventId}/payments/manual")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class ManualPaymentController(
    private val manualPaymentService: ManualPaymentService,
) {
    @PostMapping
    fun request(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: ManualPaymentRequest,
    ): ManualPaymentInstructions = manualPaymentService.request(eventId, request.tier)

    @GetMapping
    fun current(
        @PathVariable eventId: UUID,
    ): ManualPaymentInstructions =
        manualPaymentService.currentInstructions(eventId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No open activation request for this event")
}

data class ManualPaymentRequest(
    @field:NotBlank
    val tier: String,
)
