package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.shared.PhoneCodeRequested
import com.jiku.support.OrganizerApi
import com.jiku.tenant.internal.VerificationAdminService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Organization verification end to end (JIKU-175, référentiel métier §9),
 * against a real S3-compatible store that checks request signatures: phone
 * confirmation, the personal and company requests, the review desk, document
 * links that actually open, deletion on refusal and after retention, and the
 * rule that a step making clients pay needs a verified organization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration::class)
class VerificationFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var events: ApplicationEvents

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var verificationAdmin: VerificationAdminService

    private lateinit var api: OrganizerApi

    @BeforeEach
    fun setUp() {
        api = OrganizerApi(mockMvc)
    }

    @Test
    fun `a personal request needs a confirmed phone, then waits for the team`() {
        val token = api.register()
        submitPersonal(token, png()).andExpect(status().isConflict())

        api.post(token, "/api/v1/settings/verification/phone", """{"phone":"+224 620 00 00 01"}""").andExpect(status().isOk())
        api.post(token, "/api/v1/settings/verification/phone/confirm", """{"code":"000000"}""").andExpect(status().isBadRequest())
        confirmPhone(token)

        submitPersonal(token, png())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
        submitPersonal(token, png()).andExpect(status().isConflict())

        api
            .get(token, "/api/v1/settings/verification")
            .andExpect(jsonPath("$.verified").value(false))
            .andExpect(jsonPath("$.phone.verified").value(true))
            .andExpect(jsonPath("$.personal.status").value("PENDING"))
            .andExpect(jsonPath("$.limits.maxFiles").value(3))
            .andExpect(jsonPath("$.limits.maxFileBytes").value(2 * 1024 * 1024))
    }

    @Test
    fun `documents are checked by their content and size`() {
        val token = api.register()
        submitCompany(token, "not a picture".toByteArray()).andExpect(status().isUnsupportedMediaType())
        submitCompany(token, png(size = 3 * 1024 * 1024)).andExpect(status().isPayloadTooLarge())
        mockMvc
            .perform(
                multipart("/api/v1/settings/verification/company")
                    .file(MockMultipartFile("files", "rccm.png", "image/png", png()))
                    .param("legalName", "Maison Aminata SARL")
                    .param("documentType", "RCCM")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isBadRequest())
    }

    @Test
    fun `the team opens the documents, approves, and the organization may take payments`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        api.post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"VIP","priceMinor":50000}""").andExpect(status().isForbidden())
        api
            .put(token, "/api/v1/settings/payment-methods", """{"payeeName":"Maison","orangeMoneyNumber":"+224620000000"}""")
            .andExpect(status().isForbidden())
        api
            .post(
                token,
                "/api/v1/services",
                """{"name":"Consultation","timezone":"Africa/Conakry","paymentRule":"BEFORE","priceMinor":100000}""",
            ).andExpect(status().isForbidden())
        // Free steps never need verification.
        api.post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"Invité"}""").andExpect(status().isCreated())

        val document = png()
        val requestId = JsonPath.read<String>(submitCompany(token, document).andReturn().response.contentAsString, "$.id")
        val admin = adminToken()
        val queue =
            mockMvc
                .perform(get("/api/v1/admin/verifications").header("Authorization", "Bearer $admin"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        assertTrue(JsonPath.read<List<String>>(queue, "$[*].id").contains(requestId))

        val link = documentLinks(admin, requestId).single()
        val opened =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(link)).build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        assertEquals(200, opened.statusCode())
        assertContentEquals(document, opened.body())

        mockMvc
            .perform(post("/api/v1/admin/verifications/$requestId/approve").header("Authorization", "Bearer $admin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
        api.get(token, "/api/v1/settings/verification").andExpect(jsonPath("$.verified").value(true))
        api.post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"VIP","priceMinor":50000}""").andExpect(status().isCreated())

        // Past the retention period, the documents are deleted; the decision stays.
        verificationAdmin.purgeExpired(before = Instant.now().plus(Duration.ofDays(1)))
        assertTrue(documentLinks(admin, requestId).isEmpty())
        assertEquals(
            404,
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create(link)).build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode(),
        )
    }

    @Test
    fun `a refusal needs a reason, deletes the documents, and a new request may follow`() {
        val token = api.register()
        val requestId = JsonPath.read<String>(submitCompany(token, png()).andReturn().response.contentAsString, "$.id")
        val admin = adminToken()

        mockMvc
            .perform(
                post("/api/v1/admin/verifications/$requestId/reject")
                    .header("Authorization", "Bearer $admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":""}"""),
            ).andExpect(status().isBadRequest())
        mockMvc
            .perform(
                post("/api/v1/admin/verifications/$requestId/reject")
                    .header("Authorization", "Bearer $admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"The RCCM is unreadable"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.documentsAvailable").value(false))
        assertTrue(documentLinks(admin, requestId).isEmpty())

        api
            .get(token, "/api/v1/settings/verification")
            .andExpect(jsonPath("$.company.status").value("REJECTED"))
            .andExpect(jsonPath("$.company.rejectionReason").value("The RCCM is unreadable"))
        submitCompany(token, png()).andExpect(status().isOk())
    }

    private fun confirmPhone(token: String) {
        val code =
            events
                .stream(PhoneCodeRequested::class.java)
                .toList()
                .last()
                .code
        api.post(token, "/api/v1/settings/verification/phone/confirm", """{"code":"$code"}""").andExpect(status().isOk())
    }

    private fun submitPersonal(
        token: String,
        file: ByteArray,
    ) = mockMvc.perform(
        multipart("/api/v1/settings/verification/personal")
            .file(MockMultipartFile("files", "id.png", "image/png", file))
            .param("legalName", "Aminata Diallo")
            .param("documentType", "NATIONAL_ID")
            .header("Authorization", "Bearer $token"),
    )

    private fun submitCompany(
        token: String,
        file: ByteArray,
    ) = mockMvc.perform(
        multipart("/api/v1/settings/verification/company")
            .file(MockMultipartFile("files", "rccm.png", "image/png", file))
            .param("legalName", "Maison Aminata SARL")
            .param("documentType", "RCCM")
            .param("registrationNumber", "GN.TCC.2026.B.12345")
            .header("Authorization", "Bearer $token"),
    )

    private fun documentLinks(
        admin: String,
        requestId: String,
    ): List<String> =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/admin/verifications/$requestId/documents").header("Authorization", "Bearer $admin"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$[*].url",
        )

    private fun adminToken(): String {
        val email = "verification@jiku.test"
        val password = "verification-secret"
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode(password))))
        }
        return JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"$password"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )
    }

    /** A PNG signature followed by padding up to [size] bytes. */
    private fun png(size: Int = 64): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(size - 8) { (it % 251).toByte() }

    companion object {
        private const val ACCESS_KEY = "jikuaccess"
        private const val SECRET_KEY = "jikusecret123"
        private const val BUCKET = "jiku-verification"

        /**
         * SeaweedFS's S3 gateway with credentials configured: unsigned or badly
         * signed requests are refused, so these tests prove the signatures too.
         */
        private val store: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
                .withCommand("server", "-s3", "-s3.config=/etc/s3.json")
                .withCopyToContainer(
                    Transferable.of(
                        """{"identities":[{"name":"jiku","credentials":[{"accessKey":"$ACCESS_KEY","secretKey":"$SECRET_KEY"}],""" +
                            """"actions":["Admin","Read","Write","List","Tagging"]}]}""",
                    ),
                    "/etc/s3.json",
                ).withExposedPorts(8333)
                .waitingFor(
                    Wait
                        .forHttp("/")
                        .forPort(8333)
                        .forStatusCode(403)
                        .withStartupTimeout(Duration.ofMinutes(2)),
                ).also {
                    it.start()
                    createBucket(it)
                }

        private fun createBucket(container: GenericContainer<*>) {
            repeat(30) {
                val result =
                    container.execInContainer("sh", "-c", "echo 's3.bucket.create -name $BUCKET' | weed shell -master=localhost:9333")
                if (result.stdout.contains("created bucket") || result.stdout.contains("already exist")) return
                Thread.sleep(1000)
            }
            error("The test document store did not create its bucket")
        }

        @JvmStatic
        @DynamicPropertySource
        fun storage(registry: DynamicPropertyRegistry) {
            registry.add("storage.documents.endpoint") { "http://${store.host}:${store.getMappedPort(8333)}" }
            registry.add("storage.documents.region") { "us-east-1" }
            registry.add("storage.documents.bucket") { BUCKET }
            registry.add("storage.documents.access-key-id") { ACCESS_KEY }
            registry.add("storage.documents.secret-access-key") { SECRET_KEY }
        }
    }
}
