package com.jiku.backoffice.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * First-admin bootstrap (JIKU-40). Production sets both variables; when either is
 * blank no account is created, so local development is unaffected unless opted in.
 */
@ConfigurationProperties(prefix = "admin.bootstrap")
data class AdminBootstrapProperties(
    val email: String = "",
    val password: String = "",
)
