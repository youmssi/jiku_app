package com.jiku.tenant.internal

import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The invitee's side of member invitations (JIKU-50). Preview is public — the
 * accept page shows what is being joined before the visitor registers or logs
 * in; accepting requires a signed-in account whose email matches the invitation,
 * and returns tokens already bound to the joined organization.
 */
@RestController
@RequestMapping("/auth/invitations")
class MemberInvitationAcceptController(
    private val memberService: MemberService,
    private val authService: AuthService,
) {
    @GetMapping("/{token}")
    fun preview(
        @PathVariable token: String,
    ): InvitationPreview = memberService.preview(token)

    @PostMapping("/accept")
    @PreAuthorize("hasRole('USER')")
    fun accept(
        authentication: Authentication,
        @Valid @RequestBody request: AcceptInvitationRequest,
    ): AuthResponse {
        val tenantId = memberService.accept(authentication.name, request.token)
        return authService.switchOrg(authentication.name, SwitchOrgRequest(tenantId))
    }
}
