package com.jiku.tenant.internal

import com.jiku.shared.TenantContext
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Member management for the caller's active organization (JIKU-50). The route
 * guard requires the manager authority; the service re-derives the caller's
 * actual role from the membership table for the owner-only rules.
 */
@RestController
@RequestMapping("/members")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class MemberController(
    private val memberService: MemberService,
) {
    @GetMapping
    fun members(): List<MemberView> = memberService.listMembers(currentTenantId())

    @GetMapping("/invitations")
    fun invitations(): List<InvitationView> = memberService.listInvitations(currentTenantId())

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(
        authentication: Authentication,
        @Valid @RequestBody request: InviteMemberRequest,
    ): InvitationView = memberService.invite(authentication.name, currentTenantId(), request)

    @DeleteMapping("/invitations/{invitationId}")
    fun revoke(
        authentication: Authentication,
        @PathVariable invitationId: UUID,
    ) = memberService.revokeInvitation(authentication.name, currentTenantId(), invitationId)

    @PutMapping("/{userId}/role")
    fun changeRole(
        authentication: Authentication,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: ChangeRoleRequest,
    ): MemberView = memberService.changeRole(authentication.name, currentTenantId(), userId, request)

    @DeleteMapping("/{userId}")
    fun remove(
        authentication: Authentication,
        @PathVariable userId: UUID,
    ) = memberService.removeMember(authentication.name, currentTenantId(), userId)

    private fun currentTenantId(): String =
        TenantContext.get()
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "No active organization")
}
