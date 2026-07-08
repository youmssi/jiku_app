package com.jiku.shared.security

import com.jiku.shared.ApiProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
                    ).permitAll()
                it.requestMatchers("${apiProperties.basePath}/rsvp/**").permitAll()
                it.requestMatchers("${apiProperties.basePath}/checkin/**").permitAll()
                it.requestMatchers("${apiProperties.basePath}/notifications/email-feedback").permitAll()
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
