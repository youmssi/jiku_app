package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
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
 * A client opening a booking link sees who they book with (JIKU-249): the
 * organization's display name, without an account and without a tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class BookingBrandTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `the public booking page names the organization`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val serviceId =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/services", """{"name":"Coupe","timezone":"Africa/Conakry"}""")
                    .andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        val code =
            JsonPath.read<String>(
                api
                    .get(token, "/api/v1/services/$serviceId/booking-link")
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.shortCode",
            )

        mockMvc
            .perform(get("/api/v1/r/$code"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Coupe"))
            .andExpect(jsonPath("$.organizerName").value("Test Org"))
    }
}
