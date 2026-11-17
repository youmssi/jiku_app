package com.jiku.catalog.internal

import com.jiku.shared.TenantContext
import com.jiku.shared.VerificationGate
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** The way a visitor reached a service's link (JIKU-189): the QR code on its card, or the shared link. */
enum class ServiceCardSource(
    val tag: String,
) {
    CARD("card"),
    LINK("link"),
    ;

    companion object {
        /** Null for any other origin (the organization's page, a bookmark): those are not counted. */
        fun of(tag: String?): ServiceCardSource? = entries.firstOrNull { it.tag == tag?.trim()?.lowercase() }
    }
}

/** How long a service card's figures look back (JIKU-189). */
@ConfigurationProperties(prefix = "catalog.service-card")
data class ServiceCardProperties(
    val window: Duration = Duration.ofDays(30),
)

/** Opens and bookings through one way in, over the window. */
data class ServiceCardCounts(
    val card: Long,
    val link: Long,
)

/** What the organizer's Share tab shows for a service (JIKU-189). */
data class ServiceShareView(
    val code: String,
    /** Only a verified organization may share a card (ADR 106); others see it as a preview. */
    val canShare: Boolean,
    val windowDays: Long,
    val opens: ServiceCardCounts,
    val bookings: ServiceCardCounts,
)

/**
 * Counts how a service's card and link do (JIKU-189), honestly: an open counts
 * once per visitor and per day, without a tracking cookie. The visitor is a
 * hash of their address and browser salted with a secret drawn each day and
 * never stored; the hashes are deleted once their day is over. A booking counts
 * for the way in its visitor came through. WhatsApp does not say who saw a
 * card, so nothing claims to.
 */
@Service
class ServiceCardService(
    private val jdbc: JdbcTemplate,
    private val services: ServiceRepository,
    private val links: ServiceLinkCodeService,
    private val verification: VerificationGate,
    private val properties: ServiceCardProperties,
) {
    private val clock: Clock = Clock.systemUTC()
    private val salt = AtomicReference<Pair<LocalDate, ByteArray>?>(null)
    private val random = SecureRandom()

    /** Counts one open of the service behind [code], unless this visitor already opened it today. */
    @Transactional
    fun recordOpen(
        code: String,
        tag: String?,
        address: String,
        agent: String?,
    ) {
        val source = ServiceCardSource.of(tag) ?: return
        val link = links.byCode(code.uppercase()) ?: return
        val day = today()
        val visitor = visitorHash(day, link.serviceId, address, agent.orEmpty().take(AGENT_LENGTH))
        val first =
            jdbc.update(
                "INSERT INTO service_card_visitor (service_id, day, source, visitor) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                link.serviceId,
                day,
                source.tag,
                visitor,
            ) == 1
        if (first) add(link.tenantId, link.serviceId, day, source, opens = 1, bookings = 0)
    }

    /** Counts a booking for the way in its visitor came through; nothing for other origins. */
    @Transactional
    fun recordBooking(
        tenantId: String,
        serviceId: UUID,
        tag: String?,
    ) {
        val source = ServiceCardSource.of(tag) ?: return
        add(tenantId, serviceId, today(), source, opens = 0, bookings = 1)
    }

    /** The Share tab of a service of the current organization; its short link is issued on first use. */
    @Transactional
    fun share(serviceId: UUID): ServiceShareView {
        val tenantId = requireNotNull(TenantContext.get()) { "No organization in context" }
        services.findById(serviceId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found") }
        val since = today().minusDays(properties.window.toDays() - 1)
        val totals = mutableMapOf<String, Pair<Long, Long>>()
        // The tenant id is part of the filter: these rows are counters outside the
        // Hibernate filter, and one organization never reads another's.
        jdbc.query(
            "SELECT source, COALESCE(SUM(opens), 0), COALESCE(SUM(bookings), 0) FROM service_card_daily " +
                "WHERE tenant_id = ? AND service_id = ? AND day >= ? GROUP BY source",
            { rs, _ -> totals[rs.getString(1)] = rs.getLong(2) to rs.getLong(3) },
            tenantId,
            serviceId,
            since,
        )

        fun opens(source: ServiceCardSource) = totals[source.tag]?.first ?: 0

        fun bookings(source: ServiceCardSource) = totals[source.tag]?.second ?: 0
        return ServiceShareView(
            code = links.forService(serviceId, tenantId).code,
            canShare = verification.verifiedKind() != null,
            windowDays = properties.window.toDays(),
            opens = ServiceCardCounts(opens(ServiceCardSource.CARD), opens(ServiceCardSource.LINK)),
            bookings = ServiceCardCounts(bookings(ServiceCardSource.CARD), bookings(ServiceCardSource.LINK)),
        )
    }

    /** Forgets the visitor hashes of past days; returns how many went. */
    @Transactional
    fun forgetPastVisitors(): Int = jdbc.update("DELETE FROM service_card_visitor WHERE day < ?", today())

    private fun add(
        tenantId: String,
        serviceId: UUID,
        day: LocalDate,
        source: ServiceCardSource,
        opens: Long,
        bookings: Long,
    ) {
        jdbc.update(
            "INSERT INTO service_card_daily (tenant_id, service_id, day, source, opens, bookings) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (service_id, day, source) DO UPDATE SET opens = service_card_daily.opens + EXCLUDED.opens, " +
                "bookings = service_card_daily.bookings + EXCLUDED.bookings",
            tenantId,
            serviceId,
            day,
            source.tag,
            opens,
            bookings,
        )
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))

    private fun visitorHash(
        day: LocalDate,
        serviceId: UUID,
        address: String,
        agent: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(saltFor(day))
        digest.update("$serviceId|$address|$agent".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Today's secret salt, drawn at random on the first open of the day and kept in memory only. */
    private fun saltFor(day: LocalDate): ByteArray =
        salt
            .updateAndGet { current ->
                if (current?.first == day) current else day to ByteArray(SALT_BYTES).also(random::nextBytes)
            }!!
            .second

    private companion object {
        const val SALT_BYTES = 32
        const val AGENT_LENGTH = 300
    }
}

/** Deletes yesterday's visitor hashes (JIKU-189), so no visitor is followed beyond a day. */
@Component
class ServiceCardVisitorPurgeJob(
    private val cards: ServiceCardService,
) {
    @Scheduled(cron = "\${catalog.service-card.purge-cron:0 5 0 * * *}", zone = "UTC")
    @SchedulerLock(name = "ServiceCardVisitorPurgeJob.run")
    fun run() {
        cards.forgetPastVisitors()
    }
}
