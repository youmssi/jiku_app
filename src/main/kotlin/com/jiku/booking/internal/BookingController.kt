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
 * The public-facing deposit-reservation flow (JIKU-55): no organizer session
 * exists yet, so every endpoint here is either fully open (creating a booking)
 * or gated by the booking's own access token rather than a JWT.
 */
@RestController
@RequestMapping("/bookings")
class BookingController(
    private val bookingService: BookingService,
    private val properties: BookingProperties,
) {
    @GetMapping("/quote")
    fun quote(
        @RequestParam guestCountEstimate: Long,
    ): BookingQuote = bookingService.quote(guestCountEstimate)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateBookingRequest,
    ): BookingCreationResult = bookingService.create(request)

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
