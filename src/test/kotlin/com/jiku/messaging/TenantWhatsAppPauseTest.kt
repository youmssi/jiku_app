package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.InboundWhatsApp
import com.jiku.messaging.internal.TenantWhatsAppPause
import com.jiku.messaging.internal.TenantWhatsAppPausedException
import com.jiku.messaging.internal.WhatsAppInboundService
import com.jiku.messaging.internal.WhatsAppOptOut
import com.jiku.messaging.internal.WhatsAppOptOutRepository
import com.jiku.messaging.internal.WhatsAppSentMessage
import com.jiku.messaging.internal.WhatsAppSentMessageRepository
import com.jiku.shared.OpsAlert
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

/** JIKU-211: an organization whose guests write STOP or whose messages fail is paused on the Jikū number. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class TenantWhatsAppPauseTest {
    @Autowired
    lateinit var pause: TenantWhatsAppPause

    @Autowired
    lateinit var messages: WhatsAppSentMessageRepository

    @Autowired
    lateinit var optOuts: WhatsAppOptOutRepository

    @Autowired
    lateinit var inbound: WhatsAppInboundService

    @Autowired
    lateinit var events: ApplicationEvents

    @Test
    fun `too many STOPs pause the organization and alert the team once`() {
        val tenant = tenantWith(sent = 50, stops = 2)

        assertThatThrownBy { pause.assertNotPaused(tenant) }.isInstanceOf(TenantWhatsAppPausedException::class.java)
        assertThatThrownBy { pause.assertNotPaused(tenant) }.isInstanceOf(TenantWhatsAppPausedException::class.java)
        assertThat(events.stream(OpsAlert::class.java).filter { tenant in it.subject }.count()).isEqualTo(1)
    }

    @Test
    fun `too many undelivered messages pause the organization`() {
        val tenant = tenantWith(sent = 50, failed = 11)

        assertThat(pause.reason(tenant)).contains("could not deliver 11 of 50")
    }

    @Test
    fun `low rates, a small sample and messages from its own number do not pause`() {
        assertThatCode { pause.assertNotPaused(tenantWith(sent = 50, stops = 1, failed = 10)) }.doesNotThrowAnyException()
        assertThat(pause.reason(tenantWith(sent = 20, stops = 5))).isNull()
        assertThat(pause.reason(tenantWith(sent = 50, stops = 5, ownNumber = true))).isNull()
    }

    @Test
    fun `a STOP is counted against the organization whose message it answers`() {
        val tenant = tenantWith(sent = 1)
        val phone = messages.findAll().first { it.tenantId == tenant }.recipient

        inbound.handle(InboundWhatsApp(phone, text = "Stop"))

        assertThat(optOuts.findById(phone).orElseThrow().tenantId).isEqualTo(tenant)
    }

    private fun tenantWith(
        sent: Int,
        stops: Int = 0,
        failed: Int = 0,
        ownNumber: Boolean = false,
    ): String {
        val tenant = "wa-pause-${UUID.randomUUID()}"
        val prefix = (100000000..999999999).random()
        repeat(sent) { index ->
            messages.save(
                WhatsAppSentMessage(
                    wamid = "wamid.${UUID.randomUUID()}",
                    tenantId = tenant,
                    referenceId = null,
                    recipient = "224$prefix$index",
                    ownNumber = ownNumber,
                    invitation = false,
                    smsFallback = null,
                ).apply { if (index < failed) status = WhatsAppSentMessage.STATUS_FAILED },
            )
        }
        repeat(stops) { optOuts.save(WhatsAppOptOut("225$prefix$it", tenantId = tenant)) }
        return tenant
    }
}
