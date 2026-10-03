package com.jiku.live.internal

import com.jiku.live.LiveModuleApi
import com.jiku.live.LiveTicket
import com.jiku.shared.JwtService
import io.jsonwebtoken.JwtException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Signs and reads live tickets (JIKU-214). A ticket names one topic and expires;
 * it is useless as a session token, and a session token is useless here.
 */
@Service
@EnableConfigurationProperties(LiveProperties::class)
class LiveTickets(
    private val jwt: JwtService,
    private val properties: LiveProperties,
) : LiveModuleApi {
    override fun ticket(topic: String): LiveTicket =
        LiveTicket(
            ticket = jwt.sign(topic, mapOf(CLAIM_TYPE to TOKEN_TYPE), properties.ticketTtl),
            expiresAt = Instant.now().plus(properties.ticketTtl),
        )

    /** The topic a valid ticket names, or null for anything else. */
    fun topicOf(ticket: String): String? =
        try {
            val claims = jwt.parse(ticket)
            claims.subject.takeIf { claims[CLAIM_TYPE] == TOKEN_TYPE && claims.expiration != null }
        } catch (ex: JwtException) {
            null
        } catch (ex: IllegalArgumentException) {
            null
        }

    private companion object {
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "live"
    }
}
