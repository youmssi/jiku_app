package com.jiku.shared.web

import com.jiku.shared.ApiProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.HandlerTypePredicate
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Prefixes every `@RestController` mapping with the configured API base path
 * ([ApiProperties.basePath]). Controllers therefore declare only their resource
 * path (e.g. `/events`) and the version prefix is applied centrally.
 */
@Configuration
class WebConfig(
    private val apiProperties: ApiProperties,
) : WebMvcConfigurer {
    override fun configurePathMatch(configurer: PathMatchConfigurer) {
        configurer.addPathPrefix(
            apiProperties.basePath,
            HandlerTypePredicate.forAnnotation(RestController::class.java),
        )
    }
}
