package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.EmailFeedback
import com.jiku.messaging.internal.EmailFeedbackRepository
import com.jiku.messaging.internal.NotificationLog
import com.jiku.messaging.internal.NotificationLogRepository
import com.jiku.messaging.internal.TenantEmailPause
import com.jiku.messaging.internal.TenantEmailPausedException
import com.jiku.shared.OpsAlert
import com.jiku.shared.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class TenantEmailPauseTest {
    @Autowired
    lateinit var pause: TenantEmailPause

    @Autowired
    lateinit var logs: NotificationLogRepository

    @Autowired
    lateinit var feedback: EmailFeedbackRepository

    @Autowired
    lateinit var events: ApplicationEvents

    @Test
    fun `an organization whose hard bounces exceed the limit is paused and alerted once`() {
        val tenant = tenantWith(sentEmails = 50, hardBounces = 3)

        assertThat(pause.isPaused(tenant)).isTrue()
        assertThatThrownBy { pause.assertNotPaused(tenant) }.isInstanceOf(TenantEmailPausedException::class.java)
        assertThatThrownBy { pause.assertNotPaused(tenant) }.isInstanceOf(TenantEmailPausedException::class.java)
        assertThat(events.stream(OpsAlert::class.java).filter { tenant in it.subject }.count()).isEqualTo(1)
    }

    @Test
    fun `a bounce rate under the limit does not pause`() {
        val tenant = tenantWith(sentEmails = 50, hardBounces = 1)

        assertThat(pause.isPaused(tenant)).isFalse()
        assertThatCode { pause.assertNotPaused(tenant) }.doesNotThrowAnyException()
    }

    @Test
    fun `too few emails sent never pause`() {
        val tenant = tenantWith(sentEmails = 10, hardBounces = 5)

        assertThat(pause.isPaused(tenant)).isFalse()
    }

    @Test
    fun `soft bounces and other organizations' bounces do not count`() {
        val tenant = tenantWith(sentEmails = 50, hardBounces = 0, softBounces = 10)
        tenantWith(sentEmails = 50, hardBounces = 20)

        assertThat(pause.isPaused(tenant)).isFalse()
    }

    @Test
    fun `WhatsApp sends do not count toward the email sample`() {
        val tenant = tenantWith(sentEmails = 10, hardBounces = 3, sentWhatsApp = 100)

        assertThat(pause.isPaused(tenant)).isFalse()
    }

    private fun tenantWith(
        sentEmails: Int,
        hardBounces: Int,
        softBounces: Int = 0,
        sentWhatsApp: Int = 0,
    ): String {
        val tenant = "pause-${UUID.randomUUID()}"
        TenantContext.withTenant(tenant) {
            repeat(sentEmails) { logs.save(sent("EMAIL", "guest$it@example.com")) }
            repeat(sentWhatsApp) { logs.save(sent("WHATSAPP", "+2246200000$it")) }
        }
        repeat(hardBounces) { feedback.save(bounce(tenant, EmailFeedback.TYPE_HARD_BOUNCE, it)) }
        repeat(softBounces) { feedback.save(bounce(tenant, EmailFeedback.TYPE_SOFT_BOUNCE, it)) }
        return tenant
    }

    private fun sent(
        channel: String,
        recipient: String,
    ) = NotificationLog(
        referenceId = null,
        channel = channel,
        recipient = recipient,
        status = NotificationLog.STATUS_SENT,
        attempt = 1,
    )

    private fun bounce(
        tenant: String,
        type: String,
        index: Int,
    ) = EmailFeedback(recipient = "guest$index@example.com", feedbackType = type, tenantId = tenant, referenceId = null)
}
