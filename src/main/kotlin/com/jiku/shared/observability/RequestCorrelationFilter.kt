package com.jiku.shared.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Assigns every request a correlation id and puts it in the MDC so every log line
 * produced while handling the request carries it. An inbound `X-Request-Id` is
 * honoured (so a trace started at the proxy or frontend continues), otherwise a
 * fresh id is generated; either way it is echoed back on the response so a caller
 * can quote it when reporting a problem.
 *
 * Runs before every other filter (rate limiting, security) so their log output is
 * correlated too, and always clears the MDC so ids never leak across pooled threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val incoming = request.getHeader(REQUEST_ID_HEADER)
        val requestId = if (incoming.isNullOrBlank()) UUID.randomUUID().toString() else incoming
        MDC.put(MdcKeys.REQUEST_ID, requestId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MdcKeys.REQUEST_ID)
        }
    }

    private companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
    }
}
