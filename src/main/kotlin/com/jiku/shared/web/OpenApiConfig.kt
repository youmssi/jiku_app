package com.jiku.shared.web

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
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

    /**
     * Names every operation `<controller><Method>` (e.g. `ticketOrderConfirm`).
     * Springdoc's default is the bare method name, and it numbers duplicates
     * (`list_17`) in handler-discovery order, which the JVM does not guarantee:
     * the committed contract then changed from one run to the next.
     */
    @Bean
    fun stableOperationIds(): OperationCustomizer =
        OperationCustomizer { operation, handler ->
            val controller = handler.beanType.simpleName.removeSuffix("Controller")
            operation.operationId(
                controller.replaceFirstChar { it.lowercase() } +
                    handler.method.name.replaceFirstChar { it.uppercase() },
            )
        }

    /**
     * A handler mapped under two paths still yields one name twice; springdoc
     * would number the copies in discovery order. Numbering them by path instead
     * keeps each path's operation id the same on every run.
     */
    @Bean
    fun stableDuplicateOperationIds(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            val suffix = Regex("_\\d+$")
            openApi.paths
                .orEmpty()
                .flatMap { (path, item) ->
                    item.readOperationsMap().map { (method, operation) -> Triple(path, method, operation) }
                }.filter { it.third.operationId != null }
                .groupBy { it.third.operationId.replace(suffix, "") }
                .forEach { (name, uses) ->
                    uses.sortedWith(compareBy({ it.first }, { it.second })).forEachIndexed { index, use ->
                        use.third.operationId = if (index == 0) name else "${name}_$index"
                    }
                }
        }
}
