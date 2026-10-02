package com.jiku.messaging

import com.jiku.messaging.internal.WhatsAppAccountEvent
import com.jiku.messaging.internal.WhatsAppAccountEventParser
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

class WhatsAppAccountEventParserTest {
    private val mapper = JsonMapper.builder().build()

    private fun call(vararg changes: String) =
        mapper.readTree("""{"object":"whatsapp_business_account","entry":[{"id":"WABA1","changes":[${changes.joinToString(",")}]}]}""")

    @Test
    fun `reads template, number and account events and skips the rest`() {
        val root =
            call(
                """{"field":"message_template_status_update","value":{"event":"PAUSED","message_template_id":1,""" +
                    """"message_template_name":"jiku_invitation","message_template_language":"fr","reason":"LOW_QUALITY"}}""",
                """{"field":"message_template_quality_update","value":{"previous_quality_score":"GREEN",""" +
                    """"new_quality_score":"RED","message_template_id":1,"message_template_name":"jiku_invitation",""" +
                    """"message_template_language":"fr"}}""",
                """{"field":"template_category_update","value":{"message_template_id":1,""" +
                    """"message_template_name":"jiku_ticket","message_template_language":"en",""" +
                    """"previous_category":"UTILITY","new_category":"MARKETING"}}""",
                """{"field":"phone_number_quality_update","value":{"display_phone_number":"224620000000",""" +
                    """"event":"FLAGGED","current_limit":"TIER_2K"}}""",
                """{"field":"account_update","value":{"event":"ACCOUNT_VIOLATION","violation_info":{"violation_type":"SPAM"}}}""",
                """{"field":"messages","value":{"messages":[]}}""",
                """{"field":"message_template_status_update","value":{"event":"PAUSED"}}""",
            )

        assertEquals(
            listOf(
                WhatsAppAccountEvent.TemplateStatus("WABA1", "jiku_invitation", "fr", "PAUSED", "LOW_QUALITY"),
                WhatsAppAccountEvent.TemplateQuality("WABA1", "jiku_invitation", "fr", "RED"),
                WhatsAppAccountEvent.TemplateCategory("WABA1", "jiku_ticket", "en", "MARKETING"),
                WhatsAppAccountEvent.NumberQuality("WABA1", "224620000000", "FLAGGED", "TIER_2K"),
                WhatsAppAccountEvent.Account("WABA1", "ACCOUNT_VIOLATION", "SPAM"),
            ),
            WhatsAppAccountEventParser.events(root),
        )
    }

    @Test
    fun `the newer daily limit field stands in for the removed current limit`() {
        val root =
            call(
                """{"field":"phone_number_quality_update","value":{"display_phone_number":"224620000000",""" +
                    """"event":"DOWNGRADE","max_daily_conversations_per_business":"1000"}}""",
            )

        assertEquals(
            listOf(WhatsAppAccountEvent.NumberQuality("WABA1", "224620000000", "DOWNGRADE", "1000")),
            WhatsAppAccountEventParser.events(root),
        )
    }
}
