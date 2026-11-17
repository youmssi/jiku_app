package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.money.internal.GiftService
import com.jiku.support.OrganizerApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * JIKU-315: the back office offers a gift, the organizer activates it when they
 * choose and it applies like a payment would; a gift is used once, stays within
 * its organization, lapses unclaimed and can be taken back before activation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GiftFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var giftService: GiftService

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `guests wait for the organizer, then raise the chosen event's allowance once`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        api.publish(token, eventId)
        val before = allowance(token, eventId)

        val giftId = offer(api.tenantId(token), """"kind":"EVENT_GUESTS","guests":400,"days":10,"message":"Bienvenue"""")
        api
            .get(token, "/api/v1/billing/gifts")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(giftId))
            .andExpect(jsonPath("$[0].status").value("OFFERED"))
            .andExpect(jsonPath("$[0].message").value("Bienvenue"))
        assertThat(allowance(token, eventId)).isEqualTo(before)
        api
            .get(token, "/api/v1/notices")
            .andExpect(jsonPath("$.items[0].kind").value("gift.OFFERED"))

        api.post(token, "/api/v1/billing/gifts/$giftId/activate", "{}").andExpect(status().isBadRequest())
        api
            .post(token, "/api/v1/billing/gifts/$giftId/activate", """{"eventId":"$eventId"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVATED"))
            .andExpect(jsonPath("$.activatedEventId").value(eventId))
        assertThat(allowance(token, eventId)).isEqualTo(400)

        api.post(token, "/api/v1/billing/gifts/$giftId/activate", """{"eventId":"$eventId"}""").andExpect(status().isConflict())
        revoke(giftId).andExpect(status().isConflict())
    }

    @Test
    fun `plan and pack months apply like a payment, at once when the desk says so`() {
        val token = api.register()
        val tenantId = api.tenantId(token)

        offer(tenantId, """"kind":"SERVICES_PLAN","plan":"Services Pro","months":2,"applyNow":true""")
        val subscription =
            api
                .get(token, "/api/v1/billing/subscription")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        assertThat(JsonPath.read<String>(subscription, "$.plan")).isEqualTo("Services Pro")
        assertThat(JsonPath.read<String>(subscription, "$.status")).isEqualTo("ACTIVE")
        val planEnd = Instant.parse(JsonPath.read<String>(subscription, "$.expiresAt"))
        assertThat(planEnd).isAfter(Instant.now().plus(55, ChronoUnit.DAYS))

        val packGift = offer(tenantId, """"kind":"ORGANIZER_PACK","months":1""")
        api.get(token, "/api/v1/billing/pack").andExpect(jsonPath("$.active").value(false))
        api.post(token, "/api/v1/billing/gifts/$packGift/activate", "{}").andExpect(status().isOk())
        api.get(token, "/api/v1/billing/pack").andExpect(jsonPath("$.active").value(true))
    }

    @Test
    fun `a gift stays within its organization`() {
        val owner = api.register()
        val giftId = offer(api.tenantId(owner), """"kind":"ORGANIZER_PACK","months":1""")
        val stranger = api.register()
        val strangerEvent = api.createEvent(stranger)

        api.get(stranger, "/api/v1/billing/gifts").andExpect(jsonPath("$").isEmpty())
        api.post(stranger, "/api/v1/billing/gifts/$giftId/activate", "{}").andExpect(status().isNotFound())

        val guestsGift = offer(api.tenantId(owner), """"kind":"EVENT_GUESTS","guests":50,"days":5""")
        api.post(owner, "/api/v1/billing/gifts/$guestsGift/activate", """{"eventId":"$strangerEvent"}""").andExpect(status().isNotFound())
        offerRaw(api.tenantId(owner), """"kind":"EVENT_GUESTS","guests":50,"days":5,"eventId":"$strangerEvent"""")
            .andExpect(status().isNotFound())
    }

    @Test
    fun `two simultaneous activations use the gift once`() {
        val token = api.register()
        val giftId = offer(api.tenantId(token), """"kind":"ORGANIZER_PACK","months":1""")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val calls =
            (1..2).map {
                pool.submit(
                    Callable {
                        start.await()
                        api
                            .post(token, "/api/v1/billing/gifts/$giftId/activate", "{}")
                            .andReturn()
                            .response.status
                    },
                )
            }
        start.countDown()
        val statuses = calls.map { it.get() }.sorted()
        pool.shutdown()
        assertThat(statuses).containsExactly(200, 409)
    }

    @Test
    fun `an unclaimed gift lapses, and the desk can take one back before activation`() {
        val token = api.register()
        val tenantId = api.tenantId(token)
        val lapsing = offer(tenantId, """"kind":"ORGANIZER_PACK","months":1""")
        val revoked = offer(tenantId, """"kind":"ORGANIZER_PACK","months":2""")

        revoke(revoked).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"))
        assertThat(giftService.remindUnclaimed(Instant.now().plus(28, ChronoUnit.DAYS))).isGreaterThanOrEqualTo(1)
        assertThat(giftService.expireUnclaimed(Instant.now().plus(31, ChronoUnit.DAYS))).isGreaterThanOrEqualTo(1)

        api
            .get(token, "/api/v1/billing/gifts")
            .andExpect(jsonPath("$[?(@.id=='$lapsing')].status").value("EXPIRED"))
            .andExpect(jsonPath("$[?(@.id=='$revoked')].status").value("REVOKED"))
        api.post(token, "/api/v1/billing/gifts/$lapsing/activate", "{}").andExpect(status().isConflict())
    }

    @Test
    fun `the desk cannot offer what does not exist`() {
        val tenantId = api.tenantId(api.register())
        offerRaw(tenantId, """"kind":"SERVICES_PLAN","plan":"Nope","months":1""").andExpect(status().isBadRequest())
        offerRaw(tenantId, """"kind":"SERVICES_PLAN","plan":"Solo","months":1""").andExpect(status().isBadRequest())
        offerRaw(tenantId, """"kind":"EVENT_GUESTS","guests":10""").andExpect(status().isBadRequest())
        offerRaw(tenantId, """"kind":"ORGANIZER_PACK","months":99""").andExpect(status().isBadRequest())
        offerRaw(tenantId, """"kind":"EVENT_GUESTS","guests":10,"days":3,"applyNow":true""").andExpect(status().isBadRequest())
        mockMvc
            .perform(get("/api/v1/admin/gifts").header("Authorization", "Bearer ${api.register()}"))
            .andExpect(status().isForbidden())
    }

    private fun offer(
        tenantId: String,
        fields: String,
    ): String =
        JsonPath.read(
            offerRaw(tenantId, fields)
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

    private fun offerRaw(
        tenantId: String,
        fields: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/admin/gifts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantId":"$tenantId",$fields}""")
                .header("Authorization", "Bearer ${adminLogin()}"),
        )

    private fun revoke(giftId: String): ResultActions =
        mockMvc.perform(
            post("/api/v1/admin/gifts/$giftId/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"Offered by mistake"}""")
                .header("Authorization", "Bearer ${adminLogin()}"),
        )

    private fun allowance(
        token: String,
        eventId: String,
    ): Int =
        JsonPath.read(
            api
                .get(token, "/api/v1/events/$eventId/usage")
                .andReturn()
                .response.contentAsString,
            "$.allowance",
        )

    private fun adminLogin(): String {
        val email = "gifts-admin@jiku.test"
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode("admin-secret"))))
        }
        return JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"admin-secret"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )
    }
}
