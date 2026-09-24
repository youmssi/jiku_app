package com.jiku.checkin.internal

import com.jiku.catalog.OperatorAction
import com.jiku.catalog.OperatorAction.CHECK_IN
import com.jiku.catalog.OperatorAction.COLLECT
import com.jiku.catalog.OperatorModuleApi
import com.jiku.catalog.OperatorTarget
import com.jiku.ticket.MarkPaidRequest
import com.jiku.ticket.TicketInfo
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * An event's door for an operator (JIKU-23, JIKU-116), authenticated by their
 * link rather than a login. Served under `/checkin/{token}` for door links
 * pinned to one event, and under `/operator/{token}/events/{eventId}` for the
 * operator console. Opening the door only needs the event in scope; checking
 * guests in needs [OperatorAction.CHECK_IN], recording a payment
 * [OperatorAction.COLLECT]. Every action is attributed to the operator's label.
 */
@RestController
@RequestMapping("/checkin/{token}", "/operator/{token}/events/{eventId}")
class OperatorCheckInController(
    private val operators: OperatorModuleApi,
    private val checkInService: CheckInService,
) {
    @GetMapping
    fun context(
        @PathVariable token: String,
        @PathVariable(required = false) eventId: UUID? = null,
    ): ValidatorContextResponse =
        onEvent(token, eventId, null) { event -> checkInService.context(event.id, event.operatorLabel, event.actions) }

    @PostMapping("/scan")
    fun scan(
        @PathVariable token: String,
        @Valid @RequestBody request: ScanRequest,
        @PathVariable(required = false) eventId: UUID? = null,
    ): CheckInResponse =
        onEvent(token, eventId, CHECK_IN) { event -> checkInService.checkInByCode(event.id, request.ticketCode, event.operatorLabel) }

    @PostMapping("/manual")
    fun manual(
        @PathVariable token: String,
        @Valid @RequestBody request: ManualCheckInRequest,
        @PathVariable(required = false) eventId: UUID? = null,
    ): CheckInResponse =
        onEvent(token, eventId, CHECK_IN) { event -> checkInService.checkInByGuest(event.id, request.guestId, event.operatorLabel) }

    @PostMapping("/tickets/{ticketCode}/paid")
    fun markPaid(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
        @RequestBody request: MarkPaidRequest,
        @PathVariable(required = false) eventId: UUID? = null,
    ): TicketInfo =
        onEvent(token, eventId, COLLECT) { event -> checkInService.markPaid(event.id, ticketCode, request.method, event.operatorLabel) }

    @GetMapping("/search")
    fun search(
        @PathVariable token: String,
        @RequestParam("q") query: String,
        @PathVariable(required = false) eventId: UUID? = null,
    ): List<GuestMatch> = onEvent(token, eventId, CHECK_IN) { event -> checkInService.search(event.id, query) }

    @GetMapping("/stats")
    fun stats(
        @PathVariable token: String,
        @PathVariable(required = false) eventId: UUID? = null,
    ): AttendanceResponse = onEvent(token, eventId, null) { event -> checkInService.stats(event.id) }

    @GetMapping("/roster")
    fun roster(
        @PathVariable token: String,
        @PathVariable(required = false) eventId: UUID? = null,
    ): List<RosterEntry> = onEvent(token, eventId, CHECK_IN) { event -> checkInService.roster(event.id) }

    @PostMapping("/sync")
    fun sync(
        @PathVariable token: String,
        @Valid @RequestBody request: SyncRequest,
        @PathVariable(required = false) eventId: UUID? = null,
    ): List<SyncResultEntry> =
        onEvent(token, eventId, CHECK_IN) { event -> checkInService.sync(event.id, event.operatorLabel, request.items) }

    private fun <T> onEvent(
        token: String,
        eventId: UUID?,
        action: OperatorAction?,
        block: (OperatorTarget) -> T,
    ): T = operators.onEvent(token, eventId, action, block)
}
