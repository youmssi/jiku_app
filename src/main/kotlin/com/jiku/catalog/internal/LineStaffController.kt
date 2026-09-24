package com.jiku.catalog.internal

import com.jiku.catalog.OperatorAction
import com.jiku.catalog.OperatorAction.COLLECT
import com.jiku.catalog.OperatorAction.QUEUE
import com.jiku.ticket.LineActionResult
import com.jiku.ticket.MarkPaidRequest
import com.jiku.ticket.TicketInfo
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
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * A service's day line for an operator (JIKU-88, JIKU-116), authenticated by
 * their link rather than an account. Served under `/line/{token}` for counter
 * links pinned to one service, and under `/operator/{token}/services/{serviceId}`
 * for the operator console. Seeing the line only needs the service in scope;
 * moving it needs [OperatorAction.QUEUE], recording a payment
 * [OperatorAction.COLLECT].
 */
@RestController
@RequestMapping("/line/{token}", "/operator/{token}/services/{serviceId}")
class LineStaffController(
    private val gate: OperatorGate,
    private val console: DayLineConsoleService,
    private val requests: AppointmentRequestService,
) {
    @GetMapping
    fun view(
        @PathVariable token: String,
        @RequestParam(required = false) date: String? = null,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): DayLineView = onService(token, serviceId, null) { id, _ -> console.view(id, parseDate(date)) }

    /** Demandes de rendez-vous en attente de confirmation (mode « sur demande »). */
    @GetMapping("/requests")
    fun pendingRequests(
        @PathVariable token: String,
        @RequestParam(required = false) date: String? = null,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): List<PendingAppointmentRequest> = onService(token, serviceId, null) { id, _ -> requests.pending(id, parseDate(date)) }

    /** Confirme une demande en attente : le rendez-vous et son billet sont émis. */
    @PostMapping("/requests/{requestId}/accept")
    fun acceptRequest(
        @PathVariable token: String,
        @PathVariable requestId: UUID,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): Unit = onService(token, serviceId, QUEUE) { id, _ -> requests.accept(id, requestId) }

    /** Refuse une demande en attente : le créneau se libère. */
    @PostMapping("/requests/{requestId}/reject")
    fun rejectRequest(
        @PathVariable token: String,
        @PathVariable requestId: UUID,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): Unit = onService(token, serviceId, QUEUE) { id, _ -> requests.reject(id, requestId) }

    @PostMapping("/next")
    fun next(
        @PathVariable token: String,
        @RequestParam(required = false) counter: String?,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): NextResponse = onService(token, serviceId, QUEUE) { id, _ -> NextResponse(console.next(id, counter)) }

    @PostMapping("/walk-in")
    @ResponseStatus(HttpStatus.CREATED)
    fun walkIn(
        @PathVariable token: String,
        @Valid @RequestBody request: WalkInRequest,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): DayLineView = onService(token, serviceId, QUEUE) { id, _ -> console.walkIn(id, request.clientName, request.clientPhone) }

    @PostMapping("/tickets/{ticketCode}/arrive")
    fun arrive(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): LineActionResult = onService(token, serviceId, QUEUE) { id, _ -> console.arrive(id, ticketCode).orThrow() }

    @PostMapping("/tickets/{ticketCode}/call")
    fun call(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
        @RequestParam(required = false) counter: String?,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): LineActionResult = onService(token, serviceId, QUEUE) { id, _ -> console.call(id, ticketCode, counter).orThrow() }

    @PostMapping("/tickets/{ticketCode}/present")
    fun present(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): LineActionResult = onService(token, serviceId, QUEUE) { id, _ -> console.present(id, ticketCode).orThrow() }

    @PostMapping("/tickets/{ticketCode}/finish")
    fun finish(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): LineActionResult = onService(token, serviceId, QUEUE) { id, _ -> console.finish(id, ticketCode).orThrow() }

    @PostMapping("/tickets/{ticketCode}/no-show")
    fun noShow(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): LineActionResult = onService(token, serviceId, QUEUE) { id, _ -> console.noShow(id, ticketCode).orThrow() }

    @PostMapping("/tickets/{ticketCode}/paid")
    fun markPaid(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
        @RequestBody request: MarkPaidRequest,
        @PathVariable(required = false) serviceId: UUID? = null,
    ): TicketInfo = onService(token, serviceId, COLLECT) { id, label -> console.markPaid(id, ticketCode, request.method, label) }

    private fun <T> onService(
        token: String,
        serviceId: UUID?,
        action: OperatorAction?,
        block: (serviceId: UUID, label: String) -> T,
    ): T = gate.onService(token, serviceId, action) { service -> block(service.id, service.operatorLabel) }

    private fun parseDate(date: String?): LocalDate? =
        date?.let {
            try {
                LocalDate.parse(it)
            } catch (ex: RuntimeException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Date invalide : $it")
            }
        }
}
