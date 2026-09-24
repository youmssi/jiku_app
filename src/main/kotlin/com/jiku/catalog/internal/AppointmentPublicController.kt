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
 * Entrée publique du parcours client rendez-vous (JIKU-87), sans compte. Le lien
 * de service signé porte le tenant et le service ; il est résolu par
 * [AppointmentPublicService] (suspension et type vérifiés), le contexte tenant
 * est lié le temps de la requête, puis le client consulte le service et ses
 * créneaux, réserve, et consulte ou annule sa réservation par son jeton.
 */
@RestController
@RequestMapping("/appointments")
class AppointmentPublicController(
    private val appointments: AppointmentPublicService,
) {
    @GetMapping("/{token}")
    fun view(
        @PathVariable token: String,
        @RequestParam(required = false) date: String?,
    ): AppointmentServiceView = appointments.viewByToken(token, date)

    @PostMapping("/{token}/book")
    @ResponseStatus(HttpStatus.CREATED)
    fun book(
        @PathVariable token: String,
        @Valid @RequestBody request: AppointmentBookingRequest,
    ): AppointmentBookingView = appointments.bookByToken(token, request)

    @PostMapping("/{token}/line")
    @ResponseStatus(HttpStatus.CREATED)
    fun takeTicket(
        @PathVariable token: String,
        @Valid @RequestBody request: WalkInRequest,
    ): ClientLineTicketView = appointments.takeTicketByToken(token, request)

    @GetMapping("/{token}/line/{ticketCode}")
    fun lineTicket(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
    ): ClientLineTicketView = appointments.lineTicketByToken(token, ticketCode)

    @GetMapping("/{token}/booking/{bookingToken}")
    fun status(
        @PathVariable token: String,
        @PathVariable bookingToken: String,
    ): AppointmentStatusView = appointments.statusByToken(token, bookingToken)

    @DeleteMapping("/{token}/booking/{bookingToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(
        @PathVariable token: String,
        @PathVariable bookingToken: String,
    ) {
        appointments.cancelByToken(token, bookingToken)
    }
}
