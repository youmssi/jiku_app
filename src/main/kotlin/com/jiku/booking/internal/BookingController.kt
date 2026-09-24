package com.jiku.booking.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Follow-up of the deposit reservations opened before JIKU-115: status, payee
 * details and payment declarations, each gated by the booking's own access
 * token. New reservations are closed: an event's tier is now paid in one go at
 * the moment of the action (ADR 104, referentiel metier §10). This controller
 * goes once the last open reservation is closed.
 */
@RestController
@RequestMapping("/bookings")
class BookingController(
    private val bookingService: BookingService,
    private val properties: BookingProperties,
) {
    @GetMapping("/{id}")
    fun status(
        @PathVariable id: UUID,
        @RequestParam token: String,
    ): BookingStatusView = bookingService.findByToken(id, token)

    @GetMapping("/{id}/payee")
    fun payee(
        @PathVariable id: UUID,
        @RequestParam token: String,
    ): BookingPayeeDetails {
        // Validates the token before revealing payee details, same as the status endpoint.
        bookingService.findByToken(id, token)
        return BookingPayeeDetails(
            payeeName = properties.payeeName.takeIf { it.isNotBlank() },
            orangeMoneyNumber = properties.orangeMoneyNumber.takeIf { it.isNotBlank() },
            mtnMomoNumber = properties.mtnMomoNumber.takeIf { it.isNotBlank() },
        )
    }

    @PostMapping("/{id}/payment-declarations")
    @ResponseStatus(HttpStatus.CREATED)
    fun declarePayment(
        @PathVariable id: UUID,
        @RequestParam token: String,
        @Valid @RequestBody request: DeclarePaymentRequest,
    ): PaymentDeclarationResult = bookingService.declarePayment(id, token, request)
}
