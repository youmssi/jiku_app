package com.jiku.shared.security

import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Refuses to start a deployed API that still carries a development secret.
 *
 * `application.yaml` gives the signing key, the credentials encryption key and
 * the webhook secrets a development default so a fresh clone runs with no
 * `.env`. Those defaults are public in this repository: a deployed API using
 * one would sign tokens anyone can forge, or accept forged webhooks. An API is
 * taken as deployed when its public URL is HTTPS, which every real environment
 * needs for webhooks and public photos; local runs and tests use plain HTTP.
 */
@Component
class DevelopmentSecretsGuard(
    private val environment: Environment,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val publicUrl = environment.getProperty(PUBLIC_URL_KEY).orEmpty()
        if (!publicUrl.startsWith("https://")) return
        val leftovers =
            SECRET_KEYS.filter { key -> environment.getProperty(key).orEmpty().startsWith(DEVELOPMENT_PREFIX) }
        check(leftovers.isEmpty()) {
            "This API is deployed at $publicUrl but still uses the development value of " +
                "${leftovers.joinToString()}: set each one to a strong random value."
        }
    }

    private companion object {
        const val PUBLIC_URL_KEY = "catalog.banner.api-public-url"
        const val DEVELOPMENT_PREFIX = "local-development-"
        val SECRET_KEYS =
            listOf(
                "jwt.secret",
                "jiku.provider-credentials.encryption-key",
                "billing.payment.webhook-secret",
                "notification.reputation.webhook-secret",
            )
    }
}
