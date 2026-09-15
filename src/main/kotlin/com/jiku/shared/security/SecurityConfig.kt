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
                        "$auth/google",
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
                // Parcours client rendez-vous (JIKU-87) : le client n'a pas de compte,
                // il est authentifié par le lien de service signé porté dans le chemin.
                it.requestMatchers("${apiProperties.basePath}/appointments/**").permitAll()
                // Même parcours par lien court (/r/{code}) : le code remplace le jeton
                // signé dans l'URL partagée, la vérification reste côté serveur.
                it.requestMatchers("${apiProperties.basePath}/r/**").permitAll()
                // Profil public d'organisation : une page découverte par identifiant,
                // comme une carte de visite. Lecture seule, rien d'identifiant.
                it.requestMatchers(HttpMethod.GET, "${apiProperties.basePath}/public/orgs/*").permitAll()
                // Console de ligne du jour du personnel (JIKU-88) : le personnel n'a
                // pas de compte, il est authentifié par le lien signé du comptoir porté
                // dans le chemin, comme les validateurs sous /checkin.
                it.requestMatchers("${apiProperties.basePath}/line/**").permitAll()
                // Deposit-reservation flow (JIKU-55): a prospect has no account yet, so
                // every booking endpoint is either fully open or gated by the booking's
                // own access token (query param) rather than a JWT.
                it.requestMatchers("${apiProperties.basePath}/bookings/**").permitAll()
                // Liste d'accès anticipé rendez-vous (JIKU-98) : un professionnel
                // intéressé n'a pas de compte. Écriture seule et limitée ; la
                // consultation passe par /admin/prospects, qui exige PLATFORM_ADMIN.
                it.requestMatchers(HttpMethod.POST, "${apiProperties.basePath}/prospects").permitAll()
                it.requestMatchers("${apiProperties.basePath}/notifications/email-feedback/**").permitAll()
                // Mobile Money provider payment callback: the caller is the provider,
                // authenticated by the signature the payment provider verifies, not a
                // user session.
                it.requestMatchers("${apiProperties.basePath}/billing/payments/callback").permitAll()
                it.requestMatchers("/actuator/health/**").permitAll()
                // Versioned liveness endpoint for external uptime monitors (JIKU-60).
                it.requestMatchers("${apiProperties.basePath}/health").permitAll()
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
