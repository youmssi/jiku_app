package com.jiku.shared.security

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/** A deployed API never starts with a secret whose value is public in this repository. */
class DevelopmentSecretsGuardTest {
    private fun environment(
        publicUrl: String,
        jwtSecret: String,
    ) = MockEnvironment()
        .withProperty("catalog.banner.api-public-url", publicUrl)
        .withProperty("jwt.secret", jwtSecret)
        .withProperty("jiku.provider-credentials.encryption-key", "a-strong-random-encryption-key-0123456789")
        .withProperty("billing.payment.webhook-secret", "a-strong-random-payment-secret")
        .withProperty("notification.reputation.webhook-secret", "a-strong-random-webhook-secret")

    @Test
    fun `a deployed API refuses a development secret and names it`() {
        val guard = DevelopmentSecretsGuard(environment("https://api.hellojiku.com", "local-development-signing-secret"))
        assertThatThrownBy { guard.afterPropertiesSet() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("jwt.secret")
    }

    @Test
    fun `a deployed API with its own secrets starts`() {
        val guard = DevelopmentSecretsGuard(environment("https://api.hellojiku.com", "a-strong-random-signing-secret-0123456789"))
        assertThatCode { guard.afterPropertiesSet() }.doesNotThrowAnyException()
    }

    @Test
    fun `a local run keeps the development defaults`() {
        val guard = DevelopmentSecretsGuard(environment("http://localhost:8080", "local-development-signing-secret"))
        assertThatCode { guard.afterPropertiesSet() }.doesNotThrowAnyException()
    }
}
