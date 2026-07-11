package com.jiku.admin.internal

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Platform-admin login and token refresh (JIKU-40). These two endpoints are the
 * only public part of the admin surface; everything else under /admin requires
 * the PLATFORM_ADMIN role.
 */
@RestController
@RequestMapping("/admin/auth")
class AdminAuthController(
    private val authService: AdminAuthService,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: AdminLoginRequest,
    ): AdminAuthResponse = authService.login(request)

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: AdminRefreshRequest,
    ): AdminAuthResponse = authService.refresh(request)
}
