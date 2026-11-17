package com.jiku.tenant.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

data class PasskeyRegistrationRequest(
    @field:NotBlank @field:Size(max = 64) val ceremonyId: String,
    @field:NotNull val credential: JsonNode,
    @field:NotBlank @field:Size(max = 100) val label: String,
)

data class PasskeyLabelRequest(
    @field:NotBlank @field:Size(max = 100) val label: String,
)

data class PasskeySignInRequest(
    @field:NotBlank @field:Size(max = 64) val ceremonyId: String,
    @field:NotNull val credential: JsonNode,
    @field:Size(max = 64) val deviceId: String? = null,
    @field:Size(max = 500) val device: String? = null,
)

/** The signed-in account's passkeys (JIKU-318), in Settings → Security. */
@RestController
@RequestMapping("/auth/me/passkeys")
@PreAuthorize("hasRole('USER')")
class PasskeyController(
    private val passkeys: PasskeyService,
) {
    @GetMapping
    fun list(authentication: Authentication): List<PasskeyView> = passkeys.list(userId(authentication))

    /** The options the browser needs to create a passkey. */
    @PostMapping("/options")
    fun options(authentication: Authentication): PasskeyCeremony = passkeys.startRegistration(userId(authentication))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        authentication: Authentication,
        @Valid @RequestBody request: PasskeyRegistrationRequest,
    ): PasskeyView = passkeys.finishRegistration(userId(authentication), request.ceremonyId, request.credential, request.label)

    @PatchMapping("/{id}")
    fun rename(
        authentication: Authentication,
        @PathVariable id: String,
        @Valid @RequestBody request: PasskeyLabelRequest,
    ): PasskeyView = passkeys.rename(userId(authentication), id, request.label)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        authentication: Authentication,
        @PathVariable id: String,
    ) = passkeys.delete(userId(authentication), id)

    private fun userId(authentication: Authentication): UUID = UUID.fromString(authentication.name)
}

/** Signing in with a passkey (JIKU-318): no password, and no code, since it is both factors. */
@RestController
@RequestMapping("/auth/passkey")
class PasskeySignInController(
    private val passkeys: PasskeyService,
    private val authService: AuthService,
) {
    @PostMapping("/options")
    fun options(): PasskeyCeremony = passkeys.startSignIn()

    @PostMapping
    fun signIn(
        @Valid @RequestBody request: PasskeySignInRequest,
    ): AuthResponse = authService.passkeySignIn(request)
}
