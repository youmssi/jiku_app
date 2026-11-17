package com.jiku.money.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.money.AdminGiftPage
import com.jiku.money.AdminGiftView
import com.jiku.money.OfferGiftCommand
import com.jiku.shared.GiftNotice
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A gift as its organization sees it (JIKU-315). */
data class GiftView(
    val id: UUID,
    val kind: String,
    val guests: Long?,
    val days: Int?,
    val plan: String?,
    val months: Int?,
    val eventId: UUID?,
    val message: String?,
    val claimBy: Instant,
    val status: String,
    val activatedAt: Instant?,
    val activatedEventId: UUID?,
)

/**
 * Gifts (JIKU-315): the back office offers guests, plan months or pack months;
 * the organizer activates them when they choose, before the claim date. An
 * activation goes through the same entry points as a confirmed payment, minus
 * the payment, inside one transaction holding the gift's row lock, so a gift is
 * used at most once.
 */
@Service
class GiftService(
    private val gifts: GiftRepository,
    private val properties: GiftProperties,
    private val billingProperties: BillingProperties,
    private val platformSettings: PlatformBillingSettingsService,
    private val trialService: TrialService,
    private val subscriptionService: SubscriptionService,
    private val packService: OrganizerPackService,
    private val tenantModuleApi: TenantModuleApi,
    private val eventModuleApi: EventModuleApi,
    private val events: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(GiftService::class.java)
    private val transactions = TransactionTemplate(transactionManager)

    fun offer(command: OfferGiftCommand): AdminGiftView {
        val kind =
            runCatching { GiftKind.valueOf(command.kind.trim().uppercase()) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown gift kind: ${command.kind}") }
        val now = Instant.now()
        val claimBy = command.claimBy ?: now.plus(properties.claimWindow)
        if (!claimBy.isAfter(now)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The claim date must be in the future")
        val message = command.message?.trim()?.takeIf { it.isNotEmpty() }
        if ((message?.length ?: 0) > MAX_MESSAGE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The message is too long")
        }
        validate(kind, command)
        val tenantId = command.tenantId.toString()
        tenantModuleApi.findTenant(command.tenantId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found")

        return TenantContext.withTenant(tenantId) {
            if (command.eventId != null) requireOwnEvent(command.eventId)
            if (command.applyNow && kind == GiftKind.EVENT_GUESTS && command.eventId == null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the event to apply the guests to")
            }
            val gift =
                requireNotNull(
                    transactions.execute {
                        val saved =
                            gifts.save(
                                Gift(
                                    kind = kind,
                                    guests = command.guests.takeIf { kind == GiftKind.EVENT_GUESTS },
                                    days = command.days.takeIf { kind == GiftKind.EVENT_GUESTS },
                                    plan = command.plan?.trim().takeIf { kind == GiftKind.SERVICES_PLAN },
                                    months = command.months.takeIf { kind != GiftKind.EVENT_GUESTS },
                                    eventId = command.eventId.takeIf { kind == GiftKind.EVENT_GUESTS },
                                    message = message,
                                    claimBy = claimBy,
                                    createdBy = command.createdBy,
                                ),
                            )
                        if (command.applyNow) apply(saved, saved.eventId, now)
                        saved
                    },
                )
            announce(gift, if (command.applyNow) GiftNotice.KIND_APPLIED else GiftNotice.KIND_OFFERED)
            gift.toAdminView(tenantId)
        }
    }

    /** The organization's gifts, newest first. */
    fun list(): List<GiftView> = requireNotNull(transactions.execute { gifts.findAllByOrderByCreatedAtDesc().map { it.toView() } })

    /** The organizer activates a gift, choosing the event when the gift names none. */
    fun activate(
        giftId: UUID,
        eventId: UUID?,
    ): GiftView =
        requireNotNull(
            transactions.execute {
                val gift = gifts.findForUpdate(giftId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Gift not found") }
                val now = Instant.now()
                if (gift.status != GiftStatus.OFFERED) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "This gift is already ${gift.status.name.lowercase()}")
                }
                if (!gift.claimBy.isAfter(now)) throw ResponseStatusException(HttpStatus.CONFLICT, "This gift has expired")
                val target = gift.eventId ?: eventId
                if (gift.kind == GiftKind.EVENT_GUESTS) {
                    if (target == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the event to add the guests to")
                    requireOwnEvent(target)
                }
                apply(gift, target, now)
                gift.toView()
            },
        )

    fun revoke(
        giftId: UUID,
        reason: String,
    ): AdminGiftView {
        val tenantId = gifts.findTenantIdById(giftId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Gift not found")
        return TenantContext.withTenant(tenantId) {
            val gift =
                requireNotNull(
                    transactions.execute {
                        val row =
                            gifts
                                .findForUpdate(
                                    giftId,
                                ).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Gift not found") }
                        if (row.status != GiftStatus.OFFERED) {
                            throw ResponseStatusException(HttpStatus.CONFLICT, "Only a gift not yet activated can be revoked")
                        }
                        row.status = GiftStatus.REVOKED
                        row.revokedReason = reason.trim().take(MAX_MESSAGE)
                        row.updatedAt = Instant.now()
                        row
                    },
                )
            announce(gift, GiftNotice.KIND_REVOKED)
            gift.toAdminView(tenantId)
        }
    }

    fun adminList(
        status: String?,
        tenantId: UUID?,
        page: Int,
        size: Int,
    ): AdminGiftPage {
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (normalized != null && runCatching { GiftStatus.valueOf(normalized) }.isFailure) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: $status")
        }
        val effectiveSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val effectivePage = page.coerceAtLeast(0)
        val rows = gifts.adminList(normalized, tenantId?.toString(), effectiveSize, effectivePage * effectiveSize)
        val tenantNames =
            rows
                .mapNotNull { it.tenantId }
                .distinct()
                .associateWith { id -> runCatching { tenantModuleApi.findTenant(UUID.fromString(id)) }.getOrNull()?.displayName }
        val eventIds = rows.mapNotNull { it.activatedEventId ?: it.eventId }.distinct()
        val eventNames = runCatching { eventModuleApi.adminEventNames(eventIds) }.getOrDefault(emptyMap())
        return AdminGiftPage(
            entries =
                rows.map { row ->
                    val owner = row.tenantId.orEmpty()
                    row.toAdminView(owner).copy(
                        tenantName = tenantNames[owner],
                        eventName = (row.activatedEventId ?: row.eventId)?.let { eventNames[it] },
                    )
                },
            total = gifts.countAdminList(normalized, tenantId?.toString()),
            page = effectivePage,
            size = effectiveSize,
        )
    }

    /** Gifts nobody activated in time expire; returns how many. */
    fun expireUnclaimed(now: Instant = Instant.now()): Int =
        gifts.findUnclaimedPast(now).count { (id, tenantId) ->
            runCatching {
                TenantContext.withTenant(tenantId.toString()) {
                    val gift =
                        transactions.execute {
                            gifts.findForUpdate(id as UUID).orElse(null)?.takeIf { it.status == GiftStatus.OFFERED }?.also {
                                it.status = GiftStatus.EXPIRED
                                it.updatedAt = now
                            }
                        }
                    gift?.let { announce(it, GiftNotice.KIND_EXPIRED) } != null
                }
            }.onFailure { log.error("Could not expire gift {}", id, it) }.getOrDefault(false)
        }

    /** Reminds organizers of gifts about to lapse unclaimed, once each; returns how many. */
    fun remindUnclaimed(now: Instant = Instant.now()): Int =
        gifts.findClaimEndingBefore(now, now.plus(properties.claimNoticeLead)).count { (id, tenantId) ->
            runCatching {
                TenantContext.withTenant(tenantId.toString()) {
                    val gift =
                        transactions.execute {
                            gifts
                                .findForUpdate(
                                    id as UUID,
                                ).orElse(null)
                                ?.takeIf { it.status == GiftStatus.OFFERED && !it.claimNoticeSent }
                                ?.also {
                                    it.claimNoticeSent = true
                                    it.updatedAt = now
                                }
                        }
                    gift?.let { announce(it, GiftNotice.KIND_EXPIRING) } != null
                }
            }.onFailure { log.error("Could not remind gift {}", id, it) }.getOrDefault(false)
        }

    private fun apply(
        gift: Gift,
        eventId: UUID?,
        now: Instant,
    ) {
        when (gift.kind) {
            GiftKind.EVENT_GUESTS -> {
                val target = requireNotNull(eventId)
                val until = now.plus(Duration.ofDays(requireNotNull(gift.days).toLong()))
                gift.trialGrantId = trialService.lendForGift(target, requireNotNull(gift.guests), until)
                gift.activatedEventId = target
            }
            GiftKind.SERVICES_PLAN -> subscriptionService.grantGiftMonths(requireNotNull(gift.plan), requireNotNull(gift.months))
            GiftKind.ORGANIZER_PACK -> packService.grantGiftMonths(requireNotNull(gift.months))
        }
        gift.status = GiftStatus.ACTIVATED
        gift.activatedAt = now
        gift.updatedAt = now
    }

    private fun validate(
        kind: GiftKind,
        command: OfferGiftCommand,
    ) {
        when (kind) {
            GiftKind.EVENT_GUESTS -> {
                val guests = command.guests ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Say how many guests")
                if (guests < 1 || guests > billingProperties.maxGuestsPerEvent) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Give between 1 and ${billingProperties.maxGuestsPerEvent} guests",
                    )
                }
                val days = command.days ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Say for how many days")
                if (days < 1 || days > properties.maxDays) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give between 1 and ${properties.maxDays} days")
                }
            }
            GiftKind.SERVICES_PLAN -> {
                val plan = command.plan?.trim() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a plan")
                val known =
                    platformSettings.planByName(plan) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown plan: $plan")
                if (known.free) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A free plan needs no gift")
                requireMonths(command.months)
            }
            GiftKind.ORGANIZER_PACK -> requireMonths(command.months)
        }
    }

    private fun requireMonths(months: Int?) {
        if (months == null || months < 1 || months > properties.maxMonths) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give between 1 and ${properties.maxMonths} months")
        }
    }

    /** The event must belong to the organization the gift is for. */
    private fun requireOwnEvent(eventId: UUID) {
        val event = eventModuleApi.findEvent(eventId)
        if (event == null || event.tenantId != TenantContext.get()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
    }

    private fun announce(
        gift: Gift,
        kind: String,
    ) {
        val tenantId = requireNotNull(gift.tenantId)
        val tenant = runCatching { tenantModuleApi.findTenant(UUID.fromString(tenantId)) }.getOrNull()
        events.publishEvent(
            GiftNotice(
                kind = kind,
                giftId = requireNotNull(gift.id),
                tenantId = tenantId,
                giftKind = gift.kind.name,
                guests = gift.guests,
                days = gift.days,
                plan = gift.plan,
                months = gift.months,
                claimBy = gift.claimBy,
                message = gift.message,
                organizerName = tenant?.displayName ?: tenant?.name ?: "Organizer",
                organizerEmail = tenant?.contactEmail ?: "",
            ),
        )
    }

    private fun Gift.toView(): GiftView =
        GiftView(
            id = requireNotNull(id),
            kind = kind.name,
            guests = guests,
            days = days,
            plan = plan,
            months = months,
            eventId = eventId,
            message = message,
            claimBy = claimBy,
            status = status.name,
            activatedAt = activatedAt,
            activatedEventId = activatedEventId,
        )

    private fun Gift.toAdminView(tenantId: String): AdminGiftView =
        AdminGiftView(
            id = requireNotNull(id),
            tenantId = tenantId,
            kind = kind.name,
            guests = guests,
            days = days,
            plan = plan,
            months = months,
            eventId = eventId,
            message = message,
            claimBy = claimBy,
            status = status.name,
            activatedAt = activatedAt,
            activatedEventId = activatedEventId,
            revokedReason = revokedReason,
            createdBy = createdBy,
            createdAt = createdAt,
        )

    private companion object {
        const val MAX_MESSAGE = 500
        const val MAX_PAGE_SIZE = 100
    }
}
