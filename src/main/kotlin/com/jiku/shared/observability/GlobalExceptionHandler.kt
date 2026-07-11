package com.jiku.shared.observability

import com.jiku.shared.TenantContext
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Reports genuinely unhandled exceptions (those that would otherwise become an
 * opaque 500) to the [ErrorTracker] with request context, and returns a clean,
 * non-leaking body carrying the correlation id so a caller can quote it.
 *
 * Extending [ResponseEntityExceptionHandler] is deliberate: Spring's own handlers
 * for framework exceptions (validation → 400, unreadable body → 400) and for
 * `ResponseStatusException`/`ErrorResponseException` (the 404/409/410/422 the
 * services raise as normal control flow) take precedence over the catch-all below,
 * so those expected client errors keep their status and are **not** reported as
 * server errors. Only the unexpected reaches [handleUnhandled].
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val errorTracker: ErrorTracker,
) : ResponseEntityExceptionHandler() {
    /**
     * Method-security denials (`@PreAuthorize`) surface as exceptions inside MVC,
     * where the catch-all below would wrongly report them as 500s. Rethrowing
     * hands them back to Spring Security's ExceptionTranslationFilter, which
     * renders the proper 401/403.
     */
    @ExceptionHandler(AccessDeniedException::class, AuthenticationException::class)
    fun rethrowSecurityException(ex: Exception): Nothing = throw ex

    @ExceptionHandler(Exception::class)
    fun handleUnhandled(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val requestId = MDC.get(MdcKeys.REQUEST_ID)
        errorTracker.capture(
            ex,
            buildMap {
                requestId?.let { put(MdcKeys.REQUEST_ID, it) }
                TenantContext.get()?.let { put(MdcKeys.TENANT_ID, it) }
                put("path", request.requestURI)
                put("method", request.method)
            },
        )
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError("An unexpected error occurred", requestId))
    }
}

/**
 * Minimal error body. Carries the [requestId] so a user reporting a problem can
 * quote it and the team can find the matching logs; never exposes internals.
 */
data class ApiError(
    val message: String,
    val requestId: String?,
)
