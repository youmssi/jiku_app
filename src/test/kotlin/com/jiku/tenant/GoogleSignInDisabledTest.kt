package com.jiku.tenant

import com.jiku.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Without GOOGLE_OAUTH_CLIENT_ID the Google endpoint is disabled outright
 * (JIKU-51) — it must fail closed, not attempt a verification that cannot
 * succeed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GoogleSignInDisabledTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `google sign-in is refused while unconfigured`() {
        mockMvc
            .perform(
                post("/api/v1/auth/google")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"idToken":"anything"}"""),
            ).andExpect(status().isNotImplemented())
    }
}
