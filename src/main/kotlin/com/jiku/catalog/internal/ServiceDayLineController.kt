package com.jiku.catalog.internal

import com.jiku.shared.ORGANIZER_OPERATOR_LABEL
import com.jiku.ticket.LineActionResult
import com.jiku.ticket.MarkPaidRequest
import com.jiku.ticket.TicketInfo
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * Console de ligne du jour d'un service pour l'organisateur (JIKU-88) : la liste
 * du jour, l'action « suivant », l'inscription d'un sans-rendez-vous au comptoir,
 * et les transitions sur une entrée de la ligne (arrivée, appel, prise en charge,
 * fin, absent). Le personnel rejoint la même console par un lien signé
 * (`/line/{token}`).
 */
@RestController
@RequestMapping("/services/{serviceId}/day-line")
@PreAuthorize("hasRole('ORGANIZER')")
class ServiceDayLineController(
    private val console: DayLineConsoleService,
    private val requests: AppointmentRequestService,
) {
    @GetMapping
    fun view(
        @PathVariable serviceId: UUID,
        @RequestParam(required = false) date: String?,
    ): DayLineView = console.view(serviceId, parseDate(date))

    /** Demandes de rendez-vous en attente de confirmation (mode « sur demande »). */
    @GetMapping("/requests")
    fun pendingRequests(
        @PathVariable serviceId: UUID,
        @RequestParam(required = false) date: String?,
    ): List<PendingAppointmentRequest> = requests.pending(serviceId, parseDate(date))

    /** Confirme une demande en attente : le rendez-vous et son billet sont émis. */
    @PostMapping("/requests/{requestId}/accept")
    fun acceptRequest(
        @PathVariable serviceId: UUID,
        @PathVariable requestId: UUID,
    ) = requests.accept(serviceId, requestId)

    /** Refuse une demande en attente : le créneau se libère. */
    @PostMapping("/requests/{requestId}/reject")
    fun rejectRequest(
        @PathVariable serviceId: UUID,
        @PathVariable requestId: UUID,
    ) = requests.reject(serviceId, requestId)

    /** Appelle la personne suivante ; ticket nul si personne n'attend. */
    @PostMapping("/next")
    fun next(
        @PathVariable serviceId: UUID,
    ): NextResponse = NextResponse(console.next(serviceId))

    @PostMapping("/walk-in")
    @ResponseStatus(HttpStatus.CREATED)
    fun walkIn(
        @PathVariable serviceId: UUID,
        @Valid @RequestBody request: WalkInRequest,
    ): DayLineView = console.walkIn(serviceId, request.clientName, request.clientPhone)

    @PostMapping("/tickets/{ticketCode}/arrive")
    fun arrive(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
    ): LineActionResult = console.arrive(serviceId, ticketCode).orThrow()

    @PostMapping("/tickets/{ticketCode}/call")
    fun call(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
    ): LineActionResult = console.call(serviceId, ticketCode).orThrow()

    @PostMapping("/tickets/{ticketCode}/present")
    fun present(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
    ): LineActionResult = console.present(serviceId, ticketCode).orThrow()

    @PostMapping("/tickets/{ticketCode}/finish")
    fun finish(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
    ): LineActionResult = console.finish(serviceId, ticketCode).orThrow()

    @PostMapping("/tickets/{ticketCode}/no-show")
    fun noShow(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
    ): LineActionResult = console.noShow(serviceId, ticketCode).orThrow()

    @PostMapping("/tickets/{ticketCode}/paid")
    fun markPaid(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
        @RequestBody request: MarkPaidRequest,
    ): TicketInfo = console.markPaid(serviceId, ticketCode, request.method, ORGANIZER_OPERATOR_LABEL)

    private fun parseDate(date: String?): LocalDate? =
        date?.let {
            try {
                LocalDate.parse(it)
            } catch (ex: RuntimeException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Date invalide : $it")
            }
        }
}
