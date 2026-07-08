package com.jiku.shared

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * API exposure configuration. [basePath] is prepended to every REST controller's
 * mapping (see the web config), so controllers declare only their resource path
 * and the API version lives in exactly one place — moving to a new version is a
 * single configuration change rather than editing every controller.
 */
@ConfigurationProperties(prefix = "api")
data class ApiProperties(
    val basePath: String = "/api/v1",
)
