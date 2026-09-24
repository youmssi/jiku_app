package com.jiku.catalog.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Counter links for one service (JIKU-88), now operators that run that service's
 * line and record payments. The signed token is only shown at creation; the
 * short code can be shared again at any time. Revoking one revokes the operator.
 */
@RestController
@RequestMapping("/services/{serviceId}/staff-links")
@PreAuthorize("hasRole('ORGANIZER')")
class ServiceStaffController(
    private val operators: OperatorService,
) {
    @GetMapping
    fun list(
        @PathVariable serviceId: UUID,
    ): List<ServiceStaffView> = operators.counterLinks(serviceId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable serviceId: UUID,
        @Valid @RequestBody request: ServiceStaffCreateRequest,
    ): ServiceStaffCreatedResponse = operators.createCounterLink(serviceId, request.label)

    @DeleteMapping("/{staffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @PathVariable serviceId: UUID,
        @PathVariable staffId: UUID,
    ) = operators.revokeCounterLink(serviceId, staffId)
}
