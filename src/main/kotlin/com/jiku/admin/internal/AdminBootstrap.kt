package com.jiku.admin.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Creates the first platform administrator from configuration at startup, so a
 * fresh production deployment has a working back-office without any interactive
 * setup. Idempotent: an existing account with the configured email is left
 * untouched (in particular, its password is never overwritten from config).
 */
@Component
class AdminBootstrap(
    private val admins: PlatformAdminRepository,
    private val passwordEncoder: PasswordEncoder,
    private val properties: AdminBootstrapProperties,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AdminBootstrap::class.java)

    override fun run(args: ApplicationArguments) {
        if (properties.email.isBlank() || properties.password.isBlank()) {
            return
        }
        if (admins.existsByEmail(properties.email)) {
            return
        }
        admins.save(
            PlatformAdmin(
                email = properties.email,
                passwordHash = requireNotNull(passwordEncoder.encode(properties.password)),
            ),
        )
        log.info("Bootstrapped the platform administrator account")
    }
}
