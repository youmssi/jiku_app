package com.jiku.shared.web

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI document metadata and the `bearer-jwt` security scheme. Registering the
 * scheme here makes Swagger UI's "Authorize" button attach an `Authorization:
 * Bearer <token>` header, so secured endpoints can be exercised from the docs with
 * an access token obtained from the auth endpoints.
 */
@Configuration
class OpenApiConfig {
    @Bean
    fun jikuOpenApi(): OpenAPI {
        val scheme = "bearer-jwt"
        return OpenAPI()
            .info(
                Info()
                    .title("Jikū API")
                    .description("Event invitation, ticketing, RSVP and check-in API.")
                    .version("v1"),
            ).components(
                Components().addSecuritySchemes(
                    scheme,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"),
                ),
            ).addSecurityItem(SecurityRequirement().addList(scheme))
    }
}
