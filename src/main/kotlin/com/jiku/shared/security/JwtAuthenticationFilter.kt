package com.jiku.shared.security

import com.jiku.shared.JwtService
import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates a request from a Bearer access token. On a valid access token it
 * populates the Spring Security context (for `@PreAuthorize`) and, for
 * tenant-bound tokens, the [TenantContext] (so tenant-scoped persistence is
 * filtered to the caller's tenant). Platform-administrator tokens carry no tenant
 * and never populate a tenant context. A token belonging to a suspended tenant is
 * rejected outright, so suspension takes effect on the next request rather than
 * at token expiry. Both contexts are cleared after the request to avoid leaking
 * across pooled threads. An invalid or missing token simply leaves the request
 * unauthenticated.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val tenantAccessGate: TenantAccessGate,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                val claims = jwtService.parse(header.substring(BEARER_PREFIX.length))
                if (claims[JwtService.CLAIM_TOKEN_TYPE] == JwtService.TOKEN_TYPE_ACCESS) {
                    val tenantId = claims[JwtService.CLAIM_TENANT_ID] as String
                    val role = claims[JwtService.CLAIM_ROLE] as String
                    if (tenantId.isBlank() || !tenantAccessGate.isSuspended(tenantId)) {
                        if (tenantId.isNotBlank()) {
                            TenantContext.set(tenantId)
                        }
                        val authorities = TokenRoles.expand(role).map { SimpleGrantedAuthority("ROLE_$it") }
                        SecurityContextHolder.getContext().authentication =
                            UsernamePasswordAuthenticationToken(claims.subject, null, authorities)
                    }
                }
            } catch (ex: JwtException) {
                // Invalid token: leave the request unauthenticated.
                logger.debug("Rejected an invalid JWT", ex)
            }
        }
        try {
            filterChain.doFilter(request, response)
        } finally {
            TenantContext.clear()
            SecurityContextHolder.clearContext()
        }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
