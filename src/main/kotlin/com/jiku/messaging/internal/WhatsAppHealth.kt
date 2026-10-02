package com.jiku.messaging.internal

import com.jiku.shared.OpsAlert
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * WhatsApp cannot take a message right now (JIKU-209): its template is paused,
 * disabled or moved to marketing, the account is blocked, or Meta throttles.
 * Like [WhatsAppQuotaExceededException], callers queue the message (or switch
 * to SMS) instead of retrying within milliseconds.
 */
class WhatsAppUnavailableException(
    message: String,
) : RuntimeException(message)

@Embeddable
data class WhatsAppTemplateKey(
    @Column(name = "waba_id")
    val wabaId: String = "",
    @Column(name = "name")
    val name: String = "",
    @Column(name = "language")
    val language: String = "",
) : Serializable

@Entity
@Table(name = "whatsapp_template_state")
class WhatsAppTemplateState(
    @EmbeddedId
    val key: WhatsAppTemplateKey,
) {
    @Column(name = "status")
    var status: String? = null

    @Column(name = "quality")
    var quality: String? = null

    @Column(name = "category")
    var category: String? = null

    @Column(name = "reason")
    var reason: String? = null

    @Column(name = "blocked_until")
    var blockedUntil: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

interface WhatsAppTemplateStateRepository : JpaRepository<WhatsAppTemplateState, WhatsAppTemplateKey>

/** What a sender asks before using a template, and tells after Meta refused a send. */
interface WhatsAppTemplateGate {
    fun assertUsable(
        wabaId: String,
        name: String,
        language: String,
    )

    fun onSendRefused(
        wabaId: String?,
        name: String?,
        language: String,
        code: Int,
    )
}

/**
 * Keeps track of what Meta says about Jikū's WhatsApp accounts (JIKU-209) and
 * acts on it before guests notice: a paused, disabled or recategorized template
 * stops being used, so messages queue (or reminders switch to SMS) instead of
 * failing one by one; a flagged number, a quality drop or an account
 * restriction raises an ops alert, once per subject and day.
 */
@Service
class WhatsAppHealthService(
    private val states: WhatsAppTemplateStateRepository,
    private val properties: WhatsAppProperties,
    private val events: ApplicationEventPublisher,
) : WhatsAppTemplateGate {
    private val log = LoggerFactory.getLogger(WhatsAppHealthService::class.java)
    private val alerted = ConcurrentHashMap<String, LocalDate>()

    @Transactional(readOnly = true)
    override fun assertUsable(
        wabaId: String,
        name: String,
        language: String,
    ) {
        val state = states.findById(WhatsAppTemplateKey(wabaId, name, language)).orElse(null) ?: return
        val until = state.blockedUntil ?: return
        if (until.isAfter(Instant.now())) {
            throw WhatsAppUnavailableException(
                "WhatsApp template $name ($language) is ${state.status ?: state.category} until $until",
            )
        }
    }

    @Transactional
    override fun onSendRefused(
        wabaId: String?,
        name: String?,
        language: String,
        code: Int,
    ) {
        when (code) {
            in TEMPLATE_UNAVAILABLE_CODES ->
                if (wabaId != null && name != null) {
                    block(wabaId, name, language, "PAUSED", "Meta refused a send with error $code", pauseEnd())
                }
            in ACCOUNT_BLOCKED_CODES ->
                alert(
                    "account:$wabaId:$code",
                    "WhatsApp account ${wabaId ?: "(platform)"} is blocked",
                    "Meta refused a send with error $code. Messages are queued or sent by SMS until it is lifted; " +
                        "check the account in WhatsApp Manager.",
                )
        }
    }

    @Transactional
    fun onTemplateStatus(
        wabaId: String,
        name: String,
        language: String,
        event: String,
        reason: String?,
    ) {
        when (event) {
            "PAUSED" -> block(wabaId, name, language, event, reason, pauseEnd())
            "DISABLED", "REJECTED", "PENDING_DELETION" -> block(wabaId, name, language, event, reason, FOREVER)
            "APPROVED", "REINSTATED", "UNPAUSED" -> unblock(wabaId, name, language, event)
            else -> update(wabaId, name, language) { it.status = event }
        }
        if (event in ALERTED_TEMPLATE_STATUSES) {
            alert(
                "template:$wabaId:$name:$language:$event",
                "WhatsApp template $name ($language) is $event",
                "Account $wabaId. Reason: ${reason ?: "not given"}. Messages using it are queued, and reminders go by SMS.",
            )
        }
    }

    @Transactional
    fun onTemplateQuality(
        wabaId: String,
        name: String,
        language: String,
        quality: String,
    ) {
        update(wabaId, name, language) { it.quality = quality }
        if (quality == "YELLOW" || quality == "RED") {
            alert(
                "quality:$wabaId:$name:$language:$quality",
                "WhatsApp template $name ($language) quality is $quality",
                "Account $wabaId. Guests block or report these messages; Meta pauses a template whose quality " +
                    "stays low. Review who receives it and its wording.",
            )
        }
    }

    @Transactional
    fun onTemplateCategory(
        wabaId: String,
        name: String,
        language: String,
        category: String,
    ) {
        if (category == "MARKETING" && properties.health.blockMarketingTemplates) {
            block(wabaId, name, language, null, "Moved to the marketing category by Meta", FOREVER) { it.category = category }
            alert(
                "category:$wabaId:$name:$language",
                "WhatsApp template $name ($language) moved to MARKETING",
                "Account $wabaId. Marketing messages cost several times more and are capped per recipient; " +
                    "the template is no longer used until its wording is fixed and Meta files it as UTILITY again.",
            )
        } else {
            update(wabaId, name, language) {
                if (it.category == "MARKETING") {
                    it.reason = null
                    it.blockedUntil = null
                }
                it.category = category
            }
        }
    }

    fun onPhoneNumberQuality(
        wabaId: String,
        displayPhoneNumber: String?,
        event: String,
        limit: String?,
    ) {
        log.info("WhatsApp number {} ({}): {} limit={}", displayPhoneNumber, wabaId, event, limit)
        if (event == "FLAGGED" || event == "DOWNGRADE") {
            alert(
                "number:$wabaId:$displayPhoneNumber:$event",
                "WhatsApp number $displayPhoneNumber is $event",
                "Account $wabaId, messaging limit ${limit ?: "unchanged"}. Guests block or report its messages: " +
                    "check who is being sent what before the number loses more capacity.",
            )
        }
    }

    fun onAccountUpdate(
        wabaId: String,
        event: String,
        detail: String?,
    ) {
        log.info("WhatsApp account {}: {} {}", wabaId, event, detail)
        if (event in ALERTED_ACCOUNT_EVENTS) {
            alert(
                "account:$wabaId:$event",
                "WhatsApp account $wabaId: $event",
                "Detail: ${detail ?: "none"}. Check the account in WhatsApp Manager.",
            )
        }
    }

    private fun pauseEnd(): Instant = Instant.now().plus(properties.health.pauseHours, ChronoUnit.HOURS)

    private fun block(
        wabaId: String,
        name: String,
        language: String,
        status: String?,
        reason: String?,
        until: Instant,
        change: (WhatsAppTemplateState) -> Unit = {},
    ) = update(wabaId, name, language) {
        status?.let { s -> it.status = s }
        it.reason = reason?.take(REASON_LENGTH)
        it.blockedUntil = until
        change(it)
    }

    private fun unblock(
        wabaId: String,
        name: String,
        language: String,
        status: String?,
        change: (WhatsAppTemplateState) -> Unit = {},
    ) = update(wabaId, name, language) {
        status?.let { s -> it.status = s }
        it.reason = null
        it.blockedUntil = null
        change(it)
    }

    private fun update(
        wabaId: String,
        name: String,
        language: String,
        change: (WhatsAppTemplateState) -> Unit,
    ) {
        val key = WhatsAppTemplateKey(wabaId, name, language)
        val state = states.findById(key).orElseGet { WhatsAppTemplateState(key) }
        change(state)
        state.updatedAt = Instant.now()
        states.save(state)
    }

    private fun alert(
        key: String,
        subject: String,
        message: String,
    ) {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (alerted.put(key, today) == today) return
        events.publishEvent(OpsAlert(subject = "Jikū $subject", message = message))
    }

    private companion object {
        /** Meta: template paused (132015) or disabled (132016). */
        val TEMPLATE_UNAVAILABLE_CODES = setOf(132015, 132016)

        /** Meta: account locked (131031), temporarily blocked for policy violations (368). */
        val ACCOUNT_BLOCKED_CODES = setOf(131031, 368)
        val ALERTED_TEMPLATE_STATUSES = setOf("PAUSED", "DISABLED", "REJECTED", "FLAGGED", "PENDING_DELETION")
        val ALERTED_ACCOUNT_EVENTS =
            setOf("ACCOUNT_VIOLATION", "ACCOUNT_RESTRICTION", "DISABLED_UPDATE", "ACCOUNT_DELETED", "BAN", "PARTNER_REMOVED")
        val FOREVER: Instant = Instant.parse("9999-12-31T00:00:00Z")
        const val REASON_LENGTH = 500
    }
}
