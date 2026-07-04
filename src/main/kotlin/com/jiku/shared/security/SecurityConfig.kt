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
                // Mobile Money provider payment callback: the caller is the provider,
                // authenticated by the signature the payment provider verifies, not a
                // user session.
                it.requestMatchers("${apiProperties.basePath}/billing/payments/callback").permitAll()
                it.requestMatchers("/actuator/health/**").permitAll()
                it.anyRequest().authenticated()
            }.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
