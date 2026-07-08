package com.jiku.shared.web

import com.jiku.shared.ApiProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Prefixes this application's own REST controllers with the configured API base
 * path ([ApiProperties.basePath]). Controllers therefore declare only their
 * resource path (e.g. `/events`) and the version prefix is applied centrally.
 *
 * The prefix is scoped by package to `com.jiku` controllers, so library-provided
 * endpoints — notably springdoc's `/v3/api-docs` and the Swagger UI — keep their
 * conventional paths instead of being moved under the API version.
 *
 * Also configures CORS so the frontend (Next.js) can call the API from its own
 * origin. The allowed origins are set via the `jiku.cors.allowed-origins`
 * property (env var `CORS_ALLOWED_ORIGINS`).
 */
@Configuration
class WebConfig(
    private val apiProperties: ApiProperties,
) : WebMvcConfigurer {

    override fun configurePathMatch(configurer: PathMatchConfigurer) {
        configurer.addPathPrefix(apiProperties.basePath) { handlerType ->
            handlerType.packageName.startsWith("com.jiku")
        }
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        val origins = allowedOrigins.split(",").map(String::trim).filter { it.isNotEmpty() }
        registry.addMapping("/**")
            .allowedOrigins(*origins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "X-Request-ID")
            .exposedHeaders("Location", "X-Request-ID")
            .allowCredentials(true)
            .maxAge(3600)
    }

    @Value("\${jiku.cors.allowed-origins:http://localhost:3000}")
    private lateinit var allowedOrigins: String
}
