package com.jiku.shared.security

import com.jiku.shared.ApiProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Stateless JWT security. Registration, login and refresh are public; everything
 * else requires a valid access token. Method-level security (`@PreAuthorize`) is
 * enabled for role checks.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val apiProperties: ApiProperties,
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
    ): SecurityFilterChain {
        val auth = "${apiProperties.basePath}/auth"
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "$auth/register",
                        "$auth/login",
                        "$auth/refresh",
                        // Account recovery (JIKU-49): reachable by definition when
                        // the caller cannot log in. The resend endpoint is NOT here —
                        // it stays authenticated.
                        "$auth/forgot-password",
                        "$auth/reset-password",
                        "$auth/verify-email",
                    ).permitAll()
                // Invitation preview (JIKU-50): the accept page shows what is being
                // joined before the visitor registers. Accepting stays authenticated
                // (method security on the controller).
                it.requestMatchers(HttpMethod.GET, "$auth/invitations/*").permitAll()
                // Platform administration: login/refresh are public, everything else
                // under /admin requires the PLATFORM_ADMIN role (defense in depth on
                // top of the controllers' @PreAuthorize).
                it
                    .requestMatchers(
                        "${apiProperties.basePath}/admin/auth/login",
                        "${apiProperties.basePath}/admin/auth/refresh",
                    ).permitAll()
                it.requestMatchers("${apiProperties.basePath}/admin/**").hasRole("PLATFORM_ADMIN")
                it.requestMatchers("${apiProperties.basePath}/rsvp/**").permitAll()
                it.requestMatchers("${apiProperties.basePath}/checkin/**").permitAll()
                it.requestMatchers("${apiProperties.basePath}/notifications/email-feedback/**").permitAll()
                // Mobile Money provider payment callback: the caller is the provider,
                // authenticated by the signature the payment provider verifies, not a
                // user session.
                it.requestMatchers("${apiProperties.basePath}/billing/payments/callback").permitAll()
                it.requestMatchers("/actuator/health/**").permitAll()
                // API documentation (springdoc): the spec and Swagger UI are public so
                // the docs load without a token. The "Authorize" button still lets you
                // attach a JWT to try secured endpoints.
                it
                    .requestMatchers(
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                    ).permitAll()
                it.anyRequest().authenticated()
            }.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
