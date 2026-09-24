package com.jiku.catalog.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Entrée publique par lien court (/r/{code}) : même parcours que
 * /appointments/{token}, mais l'URL partagée tient sur une ligne. Le code est
 * résolu en base par [AppointmentPublicService], exactement comme le jeton signé.
 */
@RestController
@RequestMapping("/r")
class ShortLinkPublicController(
    private val appointments: AppointmentPublicService,
) {
    @GetMapping("/{code}")
    fun view(
        @PathVariable code: String,
        @RequestParam(required = false) date: String?,
    ): AppointmentServiceView = appointments.viewByCode(code, date)

    @PostMapping("/{code}/book")
    @ResponseStatus(HttpStatus.CREATED)
    fun book(
        @PathVariable code: String,
        @Valid @RequestBody request: AppointmentBookingRequest,
    ): AppointmentBookingView = appointments.bookByCode(code, request)

    @PostMapping("/{code}/line")
    @ResponseStatus(HttpStatus.CREATED)
    fun takeTicket(
        @PathVariable code: String,
        @Valid @RequestBody request: WalkInRequest,
    ): ClientLineTicketView = appointments.takeTicketByCode(code, request)

    @GetMapping("/{code}/line/{ticketCode}")
    fun lineTicket(
        @PathVariable code: String,
        @PathVariable ticketCode: String,
    ): ClientLineTicketView = appointments.lineTicketByCode(code, ticketCode)

    @GetMapping("/{code}/booking/{bookingToken}")
    fun status(
        @PathVariable code: String,
        @PathVariable bookingToken: String,
    ): AppointmentStatusView = appointments.statusByCode(code, bookingToken)

    @DeleteMapping("/{code}/booking/{bookingToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(
        @PathVariable code: String,
        @PathVariable bookingToken: String,
    ) {
        appointments.cancelByCode(code, bookingToken)
    }
}
