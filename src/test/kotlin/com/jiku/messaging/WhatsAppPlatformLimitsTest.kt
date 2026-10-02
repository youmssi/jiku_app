package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.WhatsAppPlatformLimitProperties
import com.jiku.messaging.internal.WhatsAppPlatformLimits
import com.jiku.messaging.internal.WhatsAppQuotaExceededException
import com.jiku.messaging.internal.WhatsAppSentMessage
import com.jiku.messaging.internal.WhatsAppSentMessageRepository
import com.jiku.shared.OpsAlert
import com.jiku.shared.TenantContext
import com.jiku.shared.VerificationGate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.util.UUID

/** JIKU-212: what one organization may send through the Jikū WhatsApp number. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class WhatsAppPlatformLimitsTest {
    @Autowired
    lateinit var publisher: ApplicationEventPublisher

    private var isVerified = false

    private val limits by lazy {
        WhatsAppPlatformLimits(
            messages,
            object : VerificationGate {
                override fun verifiedKind(): String? = if (isVerified) "COMPANY" else null
            },
            WhatsAppPlatformLimitProperties(unverifiedDaily = 5, monthlyBeforeOwnNumber = 8),
            publisher,
        )
    }

    @Autowired
    lateinit var messages: WhatsAppSentMessageRepository

    @Autowired
    lateinit var events: ApplicationEvents

    @Test
    fun `an unverified organization is held to a few messages a day, and told how to send more`() {
        verified(false)
        val tenant = tenantWith(sent = 5)

        TenantContext.withTenant(tenant) {
            assertThatThrownBy { limits.assertWithinLimits() }
                .isInstanceOf(WhatsAppQuotaExceededException::class.java)
                .hasMessageContaining("Verify it")
            assertThatThrownBy { limits.assertWithinLimits() }.isInstanceOf(WhatsAppQuotaExceededException::class.java)
            assertThat(limits.usage()!!.dailyLimit).isEqualTo(5)
        }
        assertThat(events.stream(OpsAlert::class.java).filter { tenant in it.subject }.count()).isEqualTo(1)
    }

    @Test
    fun `a verified organization sends past the daily limit until it needs its own number`() {
        verified(true)
        val tenant = tenantWith(sent = 7)

        TenantContext.withTenant(tenant) {
            assertThatCode { limits.assertWithinLimits() }.doesNotThrowAnyException()
            assertThat(limits.usage()!!.dailyLimit).isNull()
        }

        tenantWith(sent = 1, tenant = tenant)
        TenantContext.withTenant(tenant) {
            assertThatThrownBy { limits.assertWithinLimits() }
                .isInstanceOf(WhatsAppQuotaExceededException::class.java)
                .hasMessageContaining("own WhatsApp number")
        }
    }

    @Test
    fun `messages from the organization's own number do not count`() {
        verified(false)
        val tenant = tenantWith(sent = 20, ownNumber = true)

        TenantContext.withTenant(tenant) { assertThatCode { limits.assertWithinLimits() }.doesNotThrowAnyException() }
    }

    private fun verified(value: Boolean) {
        isVerified = value
    }

    private fun tenantWith(
        sent: Int,
        ownNumber: Boolean = false,
        tenant: String = "wa-limit-${UUID.randomUUID()}",
    ): String {
        repeat(sent) {
            messages.save(
                WhatsAppSentMessage(
                    wamid = "wamid.${UUID.randomUUID()}",
                    tenantId = tenant,
                    referenceId = null,
                    recipient = "22462000$it",
                    ownNumber = ownNumber,
                    invitation = false,
                    smsFallback = null,
                ),
            )
        }
        return tenant
    }
}
