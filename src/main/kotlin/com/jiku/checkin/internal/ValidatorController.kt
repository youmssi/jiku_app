package com.jiku.checkin.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Organizer-facing management of validator access links for one of their events.
 * Tenant is bound from the JWT; the event is scoped by the path.
 */
@RestController
@RequestMapping("/events/{eventId}/validators")
@PreAuthorize("hasRole('ORGANIZER')")
class ValidatorController(
    private val validatorService: ValidatorService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: CreateValidatorRequest,
    ): ValidatorResponse = validatorService.generate(eventId, request.label)

    @GetMapping
    fun list(
        @PathVariable eventId: UUID,
    ): List<ValidatorResponse> = validatorService.list(eventId)

    @PostMapping("/{validatorId}/revoke")
    fun revoke(
        @PathVariable eventId: UUID,
        @PathVariable validatorId: UUID,
    ): ValidatorResponse = validatorService.revoke(eventId, validatorId)
}
