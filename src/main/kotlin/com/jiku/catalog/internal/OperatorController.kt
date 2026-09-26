package com.jiku.catalog.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** The organizer manages their operators (JIKU-116). */
@RestController
@RequestMapping("/operators")
@PreAuthorize("hasRole('ORGANIZER')")
class OperatorController(
    private val operators: OperatorService,
) {
    @GetMapping
    fun team(): OperatorTeamView = operators.team()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: OperatorRequest,
    ): OperatorView = operators.create(request)

    @PutMapping("/{operatorId}")
    fun update(
        @PathVariable operatorId: UUID,
        @Valid @RequestBody request: OperatorRequest,
    ): OperatorView = operators.update(operatorId, request)

    @PostMapping("/{operatorId}/revoke")
    fun revoke(
        @PathVariable operatorId: UUID,
    ): OperatorView = operators.revoke(operatorId)
}

/**
 * The operator's own entry points, authenticated by their link rather than a
 * login: the short code resolves into a signed link, and the link opens the
 * console listing the events and services the operator works on. Each event's
 * door and each service's line are then served under `/operator/{token}/events/…`
 * and `/operator/{token}/services/…`.
 */
@RestController
class OperatorConsoleController(
    private val operators: OperatorService,
    private val gate: OperatorGate,
) {
    @GetMapping("/operator-codes/{code}")
    fun resolve(
        @PathVariable code: String,
    ): OperatorLinkResolution = operators.resolveCode(code)

    @GetMapping("/operator/{token}")
    fun console(
        @PathVariable token: String,
    ): OperatorConsoleView = gate.asOperator(token) { operator, _ -> operators.console(operator) }
}

/**
 * Door links for one event (JIKU-23), now operators that check guests in and
 * record payments at that event. Revoking one revokes the operator.
 */
@RestController
@RequestMapping("/events/{eventId}/validators")
@PreAuthorize("hasRole('ORGANIZER')")
class ValidatorController(
    private val operators: OperatorService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: CreateValidatorRequest,
    ): ValidatorResponse = operators.createDoorLink(eventId, request.label)

    @GetMapping
    fun list(
        @PathVariable eventId: UUID,
    ): List<ValidatorResponse> = operators.doorLinks(eventId)

    @PostMapping("/{validatorId}/revoke")
    fun revoke(
        @PathVariable eventId: UUID,
        @PathVariable validatorId: UUID,
    ): ValidatorResponse = operators.revokeDoorLink(eventId, validatorId)
}
