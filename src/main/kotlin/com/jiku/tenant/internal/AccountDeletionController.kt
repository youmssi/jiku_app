package com.jiku.tenant.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Deleting one's own account (JIKU-316): what it would take with it, and the
 * request itself, confirmed by the password or a fresh Google sign-in.
 */
@RestController
@RequestMapping("/auth/me/deletion")
class AccountDeletionController(
    private val deletion: AccountDeletionService,
) {
    @GetMapping
    fun status(authentication: Authentication): AccountDeletionStatusView = deletion.status(UUID.fromString(authentication.name))

    @PostMapping
    fun request(
        authentication: Authentication,
        @Valid @RequestBody request: AccountDeletionRequest,
    ): AccountDeletionStatusView =
        deletion.request(
            userId = UUID.fromString(authentication.name),
            password = request.password,
            googleIdToken = request.googleIdToken,
            deleteOrganizations = request.deleteOrganizations,
        )
}

data class AccountDeletionRequest(
    @field:Size(max = 200)
    val password: String? = null,
    @field:Size(max = 4096)
    val googleIdToken: String? = null,
    /** Agrees to delete the organizations the account owns alone. */
    val deleteOrganizations: Boolean = false,
)
