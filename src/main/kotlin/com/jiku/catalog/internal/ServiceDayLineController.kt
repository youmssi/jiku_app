package com.jiku.catalog.internal

import com.jiku.ticket.LineActionResult
import com.jiku.ticket.LineOutcome
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
) {
    @GetMapping
    fun view(
        @PathVariable serviceId: UUID,
        @RequestParam(required = false) date: String?,
    ): DayLineView = console.view(serviceId, parseDate(date))

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
    ): LineActionResult = reply(console.arrive(serviceId, ticketCode))

    @PostMapping("/tickets/{ticketCode}/call")
    fun call(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
    ): LineActionResult = reply(console.call(serviceId, ticketCode))

    @PostMapping("/tickets/{ticketCode}/present")
    fun present(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
    ): LineActionResult = reply(console.present(serviceId, ticketCode))

    @PostMapping("/tickets/{ticketCode}/finish")
    fun finish(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
    ): LineActionResult = reply(console.finish(serviceId, ticketCode))

    @PostMapping("/tickets/{ticketCode}/no-show")
    fun noShow(
        @PathVariable serviceId: UUID,
        @PathVariable ticketCode: String,
    ): LineActionResult = reply(console.noShow(serviceId, ticketCode))

    private fun reply(result: LineActionResult): LineActionResult =
        when (result.outcome) {
            LineOutcome.OK -> result
            LineOutcome.NOT_FOUND ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune entrée avec ce code sur ce service")
            LineOutcome.WRONG_STATE ->
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cette entrée n'est plus dans l'état attendu — elle a peut-être été traitée par un autre poste",
                )
        }

    private fun parseDate(date: String?): LocalDate? =
        date?.let {
            try {
                LocalDate.parse(it)
            } catch (ex: RuntimeException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Date invalide : $it")
            }
        }
}
