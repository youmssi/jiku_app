package com.jiku.shared.web

import com.jiku.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * JIKU-60: an external uptime monitor must be able to reach this endpoint with no
 * credentials and no knowledge of `/actuator/health`'s authorization rules.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HealthControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `health endpoint is reachable without authentication and reports UP`() {
        mockMvc
            .perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.version").isNotEmpty())
    }
}
