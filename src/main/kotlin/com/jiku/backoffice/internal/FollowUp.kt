package com.jiku.backoffice.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.ServiceModuleApi
import com.jiku.invitation.InvitationModuleApi
import com.jiku.messaging.NotificationModuleApi
import com.jiku.money.BillingModuleApi
import com.jiku.tenant.TenantActivationProfile
import com.jiku.tenant.TenantModuleApi
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Why an organization should get a call (JIKU-202). Each reason is the next step
 * it has not taken; the team follows up by hand, on WhatsApp, during the pilot.
 */
enum class FollowUpReason {
    /** Created more than two days ago, still no event and no service. */
    NO_ACTIVITY,

    /** Has an event for more than two days, still no guest. */
    NO_GUESTS,

    /** Its verification was refused and needs a new submission. */
    VERIFICATION_REJECTED,

    /** A trial ends within three days and nothing has been paid yet. */
    TRIAL_ENDING,
}

@Entity
@Table(name = "follow_up_done")
class FollowUpDone(
    @Column(name = "tenant_id", nullable = false)
    val tenantId: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    val reason: FollowUpReason,
    @Column(name = "done_at", nullable = false)
    val doneAt: Instant,
) {
    @Id
    var id: UUID = UUID.randomUUID()
}

interface FollowUpDoneRepository : JpaRepository<FollowUpDone, UUID> {
    fun findByDoneAtAfter(after: Instant): List<FollowUpDone>
}

data class FollowUpEntry(
    val tenantId: String,
    val organizationName: String,
    val ownerName: String?,
    val ownerEmail: String?,
    val phone: String?,
    val marketingConsent: Boolean,
    val reason: FollowUpReason,
    /** When the organization became eligible for this follow-up. */
    val since: Instant,
)

/** Organizations created in the window and how far they went (JIKU-202). */
data class ActivationFunnel(
    val windowDays: Long,
    val signedUp: Int,
    val firstEventOrService: Int,
    val firstSend: Int,
    val firstPayment: Int,
)

data class FollowUpOverview(
    val entries: List<FollowUpEntry>,
    val funnel: ActivationFunnel,
)

/**
 * Builds the back-office follow-up list and activation funnel from what each
 * module exposes (JIKU-202). Nothing here reads another module's tables.
 */
@Service
class FollowUpService(
    private val tenants: TenantModuleApi,
    private val events: EventModuleApi,
    private val services: ServiceModuleApi,
    private val invitations: InvitationModuleApi,
    private val notifications: NotificationModuleApi,
    private val billing: BillingModuleApi,
    private val done: FollowUpDoneRepository,
) {
    @Transactional(readOnly = true)
    fun overview(): FollowUpOverview {
        val now = Instant.now()
        val profiles = tenants.adminActivationProfiles()
        val firstEvent = events.adminFirstEventAt()
        val firstService = services.adminFirstServiceAt()
        val firstGuest = invitations.adminFirstGuestAt()
        val firstSend = notifications.adminFirstSendAt()
        val firstPayment = billing.adminFirstPaymentAt()
        val trialEnds = billing.adminActiveTrialEnds()
        val recentlyDone =
            done
                .findByDoneAtAfter(now.minus(QUIET_PERIOD))
                .map { it.tenantId to it.reason }
                .toSet()

        val entries =
            profiles
                .flatMap { profile ->
                    val id = profile.tenantId
                    listOfNotNull(
                        noActivity(profile, firstEvent[id] ?: firstService[id], now),
                        noGuests(profile, firstEvent[id], firstGuest[id], now),
                        verificationRejected(profile),
                        trialEnding(profile, trialEnds[id], firstPayment[id], now),
                    )
                }.filter { (it.tenantId to it.reason) !in recentlyDone }
                .sortedBy { it.since }

        val cohort = profiles.filter { it.createdAt.isAfter(now.minus(FUNNEL_WINDOW)) }.map { it.tenantId }
        val funnel =
            ActivationFunnel(
                windowDays = FUNNEL_WINDOW.toDays(),
                signedUp = cohort.size,
                firstEventOrService = cohort.count { it in firstEvent || it in firstService },
                firstSend = cohort.count { it in firstSend },
                firstPayment = cohort.count { it in firstPayment },
            )
        return FollowUpOverview(entries, funnel)
    }

    @Transactional
    fun markDone(
        tenantId: String,
        reason: FollowUpReason,
    ) {
        done.save(FollowUpDone(tenantId = tenantId, reason = reason, doneAt = Instant.now()))
    }

    private fun noActivity(
        profile: TenantActivationProfile,
        firstActivity: Instant?,
        now: Instant,
    ): FollowUpEntry? {
        if (firstActivity != null) return null
        val since = profile.createdAt.plus(GRACE)
        return if (since.isBefore(now)) profile.entry(FollowUpReason.NO_ACTIVITY, since) else null
    }

    private fun noGuests(
        profile: TenantActivationProfile,
        firstEvent: Instant?,
        firstGuest: Instant?,
        now: Instant,
    ): FollowUpEntry? {
        if (firstEvent == null || firstGuest != null) return null
        val since = firstEvent.plus(GRACE)
        return if (since.isBefore(now)) profile.entry(FollowUpReason.NO_GUESTS, since) else null
    }

    private fun verificationRejected(profile: TenantActivationProfile): FollowUpEntry? =
        if (profile.verificationStatus == REJECTED) profile.entry(FollowUpReason.VERIFICATION_REJECTED, profile.createdAt) else null

    private fun trialEnding(
        profile: TenantActivationProfile,
        trialEnd: Instant?,
        firstPayment: Instant?,
        now: Instant,
    ): FollowUpEntry? {
        if (trialEnd == null || firstPayment != null || trialEnd.isAfter(now.plus(TRIAL_NOTICE))) return null
        return profile.entry(FollowUpReason.TRIAL_ENDING, trialEnd.minus(TRIAL_NOTICE))
    }

    private fun TenantActivationProfile.entry(
        reason: FollowUpReason,
        since: Instant,
    ) = FollowUpEntry(
        tenantId = tenantId,
        organizationName = name,
        ownerName = ownerName,
        ownerEmail = ownerEmail,
        phone = phone,
        marketingConsent = ownerMarketingConsent,
        reason = reason,
        since = since,
    )

    private companion object {
        val GRACE: Duration = Duration.ofHours(48)
        val TRIAL_NOTICE: Duration = Duration.ofDays(3)
        val QUIET_PERIOD: Duration = Duration.ofDays(7)
        val FUNNEL_WINDOW: Duration = Duration.ofDays(30)
        const val REJECTED = "REJECTED"
    }
}

/** Back-office follow-up list and activation funnel (JIKU-202). */
@RestController
@RequestMapping("/admin/follow-ups")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminFollowUpController(
    private val service: FollowUpService,
) {
    @GetMapping
    fun overview(): FollowUpOverview = service.overview()

    @PostMapping("/{tenantId}/{reason}/done")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markDone(
        @PathVariable tenantId: UUID,
        @PathVariable reason: FollowUpReason,
    ) = service.markDone(tenantId.toString(), reason)
}
