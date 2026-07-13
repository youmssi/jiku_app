package com.jiku.tenant.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Organization creation (JIKU-48). Any signed-in account can create an
 * organization and becomes its OWNER; the response tokens are already bound to
 * the new organization so the frontend lands straight in it.
 */
@RestController
@RequestMapping("/orgs")
class OrgController(
    private val authService: AuthService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('USER')")
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CreateOrgRequest,
    ): AuthResponse = authService.createOrganization(authentication.name, request)
}
