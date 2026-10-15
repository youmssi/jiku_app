package com.jiku.shared.ratelimit

import com.jiku.shared.ApiProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Enforces the configured [RateLimitProperties] policies on every request whose
 * path matches one, before any authentication or handler work happens. Exceeding a
 * budget answers `429 Too Many Requests` with a `Retry-After` header rather than a
 * generic error or a silent drop.
 *
 * Keys combine the policy name, the client IP and — for link-token flows — the
 * token path segment, so a busy venue NAT does not lump every validator together
 * and a scanning attacker cannot rotate tokens for a fresh budget. The client IP
 * is the one the web server forwards for its visitor when it proves itself with
 * [RateLimitProperties.forwardedClientIpSecret], the connection's address otherwise.
 */
@Component
// Runs just before the Spring Security filter chain (registered at -100 by
// default), so floods are rejected before JWT parsing or handler mapping.
@Order(-110)
class RateLimitFilter(
    private val properties: RateLimitProperties,
    private val apiProperties: ApiProperties,
    private val limiter: RateLimiter,
) : OncePerRequestFilter() {
    private val pathMatcher = AntPathMatcher()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !properties.enabled

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val relativePath = relativePath(request)
        val policy = if (relativePath == null) null else matchPolicy(relativePath)
        if (relativePath == null || policy == null) {
            filterChain.doFilter(request, response)
            return
        }
        val (name, limits) = policy
        val retryAfter = limiter.tryAcquire(key(name, limits, request, relativePath), limits.maxRequests, limits.window)
        if (retryAfter == null) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader("Retry-After", retryAfter.seconds.toString())
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"message":"Too many requests; please wait a moment and try again"}""")
    }

    /** The request path relative to the API base path, or null when outside it. */
    private fun relativePath(request: HttpServletRequest): String? {
        val basePath = apiProperties.basePath
        val uri = request.requestURI
        if (!uri.startsWith("$basePath/")) {
            return null
        }
        return uri.removePrefix(basePath)
    }

    private fun matchPolicy(relativePath: String): Pair<String, RateLimitProperties.Policy>? =
        properties.policies.entries
            .firstOrNull { (_, policy) -> policy.paths.any { pathMatcher.match(it, relativePath) } }
            ?.toPair()

    private fun key(
        policyName: String,
        policy: RateLimitProperties.Policy,
        request: HttpServletRequest,
        relativePath: String,
    ): String {
        val token =
            policy.tokenSegment?.let { index ->
                relativePath.trim('/').split('/').getOrNull(index)
            }
        return listOfNotNull(policyName, clientIp(request), token).joinToString(":")
    }

    private fun clientIp(request: HttpServletRequest): String {
        val secret = properties.forwardedClientIpSecret
        if (secret.isBlank()) return request.remoteAddr
        val presented = request.getHeader(CLIENT_IP_SECRET_HEADER) ?: return request.remoteAddr
        if (!MessageDigest.isEqual(presented.toByteArray(), secret.toByteArray())) return request.remoteAddr
        val forwarded = request.getHeader(CLIENT_IP_HEADER)?.trim()
        return forwarded?.takeIf { IP_LITERAL.matches(it) } ?: request.remoteAddr
    }

    companion object {
        const val CLIENT_IP_HEADER = "X-Jiku-Client-Ip"
        const val CLIENT_IP_SECRET_HEADER = "X-Jiku-Client-Ip-Secret"

        /** An IPv4 or IPv6 literal: digits, hex letters, dots and colons, nothing else. */
        private val IP_LITERAL = Regex("^[0-9A-Fa-f:.]{2,45}$")
    }
}
