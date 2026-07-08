package com.jiku.shared.web

import com.jiku.shared.ApiProperties
import org.springframework.context.annotation.Configuration
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
}
