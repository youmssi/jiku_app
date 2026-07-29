package com.jiku.shared.web

import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Lightweight, versioned liveness endpoint (JIKU-60) for external uptime monitors
 * (e.g. UptimeRobot) that should not need to know about `/actuator/health` or its
 * authorization rules. `/actuator/health` remains the readiness probe used for
 * deeper checks (database connectivity); this endpoint only confirms the process
 * is up and answering requests.
 */
@RestController
class HealthController(
    private val buildProperties: BuildProperties?,
) {
    @GetMapping("/health")
    fun health(): Map<String, String> =
        mapOf(
            "status" to "UP",
            "version" to (buildProperties?.version ?: "unknown"),
        )
}
