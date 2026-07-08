package com.jiku

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18"))

    /**
     * Integration tests share one application context and hammer the public
     * endpoints far past any sensible production budget, so the public rate
     * limits are off here. The filter's behavior has its own dedicated tests.
     */
    @Bean
    fun rateLimitDisabled(): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("api.rate-limit.enabled") { "false" }
        }
}
