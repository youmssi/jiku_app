package com.jiku.backoffice

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * JIKU-133: an organizer rates key actions and writes to the platform team;
 * the team reads, triages and summarizes it from the admin desk.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class FeedbackFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    private lateinit var api: OrganizerApi

    @BeforeEach
    fun setUp() {
        api = OrganizerApi(mockMvc)
    }

    @Test
    fun `a moment is asked once, and no second prompt comes within the week`() {
        val token = api.register()

        api.get(token, "/api/v1/feedback/prompt?moment=event_published").andExpect(jsonPath("$.show").value(true))
        api
            .post(token, "/api/v1/feedback/ratings", """{"moment":"event_published","score":4,"comment":"Clear"}""")
            .andExpect(status().isCreated())

        api.get(token, "/api/v1/feedback/prompt?moment=event_published").andExpect(jsonPath("$.show").value(false))
        api.get(token, "/api/v1/feedback/prompt?moment=invitations_sent").andExpect(jsonPath("$.show").value(false))
    }

    @Test
    fun `a dismissed prompt counts as asked`() {
        val token = api.register()

        api.post(token, "/api/v1/feedback/ratings", """{"moment":"service_created"}""").andExpect(status().isCreated())

        api.get(token, "/api/v1/feedback/prompt?moment=service_created").andExpect(jsonPath("$.show").value(false))
    }

    @Test
    fun `answering the same moment again updates the rating instead of adding one`() {
        val token = api.register()

        val first = idOf(api.post(token, "/api/v1/feedback/ratings", """{"moment":"first_scan","score":2}"""))
        val second = idOf(api.post(token, "/api/v1/feedback/ratings", """{"moment":"first_scan","score":5}"""))

        assert(first == second)
    }

    @Test
    fun `a message needs a known kind and a text`() {
        val token = api.register()

        api.post(token, "/api/v1/feedback", """{"kind":"RATING","message":"not a message kind"}""").andExpect(status().isBadRequest())
        api.post(token, "/api/v1/feedback", """{"kind":"IDEA","message":""}""").andExpect(status().isBadRequest())
        api.get(token, "/api/v1/feedback/prompt?moment=Not-A-Moment").andExpect(status().isBadRequest())
    }

    @Test
    fun `the team triages messages and reads the rating summary, organizers cannot`() {
        val token = api.register()
        val id =
            idOf(
                api.post(
                    token,
                    "/api/v1/feedback",
                    """{"kind":"COMPLAINT","message":"The scan refused a valid ticket","page":"/events/x","contactEmail":"org@example.com"}""",
                ),
            )
        api.post(token, "/api/v1/feedback/ratings", """{"moment":"payment_completed","score":3}""")

        api.get(token, "/api/v1/admin/feedback").andExpect(status().isForbidden())

        val admin = adminLogin()
        api
            .get(admin, "/api/v1/admin/feedback?kind=COMPLAINT&status=NEW&size=100")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.id == '$id')].message").value("The scan refused a valid ticket"))
        api
            .post(admin, "/api/v1/admin/feedback/$id/status", """{"status":"ANSWERED","note":"Replied by email"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ANSWERED"))
            .andExpect(jsonPath("$.adminNote").value("Replied by email"))
        api
            .get(admin, "/api/v1/admin/feedback/ratings?days=30")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.moment == 'payment_completed')].responses").isNotEmpty())
    }

    private fun idOf(result: ResultActions): String {
        val body =
            result
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.id")
    }

    private fun adminLogin(): String {
        val email = "feedback-admin@jiku.test"
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode("admin-secret"))))
        }
        val body =
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"admin-secret"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
