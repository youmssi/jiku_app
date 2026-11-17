package com.jiku.tenant.internal

import com.jiku.shared.AccountDeletionNotice
import com.jiku.shared.CodedRefusal
import com.jiku.shared.MessageLanguage
import com.jiku.shared.OrganizationClosed
import com.jiku.shared.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/** An organization only this account owns, so it cannot outlive the account. */
data class SoleOwnedOrganization(
    val id: String,
    val name: String,
)

/** Where the caller's account stands regarding deletion (JIKU-316). */
data class AccountDeletionStatusView(
    /** When the account will be erased; null when no deletion is pending. */
    val scheduledFor: Instant?,
    /** Organizations deleted with the account unless their ownership is shared first. */
    val soleOwnedOrganizations: List<SoleOwnedOrganization>,
    /** Whether confirming takes the password, or a fresh Google sign-in. */
    val confirmWithPassword: Boolean,
)

/**
 * Deleting an organizer account (JIKU-316). Asking disables the account at
 * once and closes the organizations it owns alone, when the holder agrees to
 * delete them; signing in again within the grace period cancels everything;
 * at the end, the account and those organizations' personal data are erased,
 * module by module, while invoices stay intact for the legal retention period.
 */
@Service
class AccountDeletionService(
    private val deletions: AccountDeletionRepository,
    private val users: OrganizerUserRepository,
    private val memberships: OrganizerMembershipRepository,
    private val tenants: TenantRepository,
    private val tenantGate: TenantAccessGateAdapter,
    private val passwordEncoder: PasswordEncoder,
    private val googleVerifier: GoogleIdentityVerifier,
    private val accountProperties: AccountProperties,
    private val properties: AccountDeletionProperties,
    private val events: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(AccountDeletionService::class.java)
    private val transactions = TransactionTemplate(transactionManager)

    /** Sign-in runs read-only; cancelling a deletion there writes in a transaction of its own. */
    private val ownTransactions =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun status(userId: UUID): AccountDeletionStatusView {
        val user = requireUser(userId)
        return AccountDeletionStatusView(
            scheduledFor = deletions.findFirstByUserIdAndStatus(userId, AccountDeletionStatus.PENDING)?.scheduledFor,
            soleOwnedOrganizations = soleOwned(userId),
            confirmWithPassword = user.passwordHash != null,
        )
    }

    fun request(
        userId: UUID,
        password: String?,
        googleIdToken: String?,
        deleteOrganizations: Boolean,
    ): AccountDeletionStatusView {
        val user = requireUser(userId)
        confirmIdentity(user, password, googleIdToken)
        val owned = soleOwned(userId)
        if (owned.isNotEmpty() && !deleteOrganizations) {
            throw CodedRefusal(
                HttpStatus.CONFLICT,
                CodedRefusal.SOLE_OWNER,
                "Share the ownership of your organizations, or agree to delete them with your account",
            )
        }
        val now = Instant.now()
        val language = MessageLanguage.normalize(LocaleContextHolder.getLocale().toLanguageTag())
        val deletion =
            requireNotNull(
                transactions.execute {
                    if (deletions.findFirstByUserIdAndStatus(userId, AccountDeletionStatus.PENDING) != null) {
                        throw ResponseStatusException(HttpStatus.CONFLICT, "This account is already being deleted")
                    }
                    owned.forEach { setStatus(it.id, TenantStatus.CLOSING) }
                    // Every session ends now: refresh tokens issued before this instant are refused.
                    user.passwordChangedAt = now
                    users.save(user)
                    deletions.save(
                        AccountDeletion(
                            userId = userId,
                            email = user.email,
                            language = language,
                            tenantIds = owned.joinToString(",") { it.id },
                            scheduledFor = now.plus(properties.grace),
                        ),
                    )
                },
            )
        events.publishEvent(
            AccountDeletionNotice(
                kind = AccountDeletionNotice.KIND_REQUESTED,
                email = user.email,
                language = language,
                scheduledFor = deletion.scheduledFor,
                organizations = owned.map { it.name },
                actionUrl = link(language, "/login"),
            ),
        )
        return status(userId)
    }

    /** Signing in again before the end cancels a pending deletion and reopens its organizations. */
    fun cancelOnSignIn(user: OrganizerUser) {
        val userId = user.id ?: return
        val cancelled =
            ownTransactions.execute {
                deletions.findFirstByUserIdAndStatus(userId, AccountDeletionStatus.PENDING)?.also { deletion ->
                    deletion.tenants().forEach { setStatus(it, TenantStatus.ACTIVE) }
                    deletion.status = AccountDeletionStatus.CANCELLED
                    deletion.finishedAt = Instant.now()
                    deletion.email = null
                }
            } ?: return
        events.publishEvent(
            AccountDeletionNotice(kind = AccountDeletionNotice.KIND_CANCELLED, email = user.email, language = cancelled.language),
        )
    }

    /** Carries out the deletions whose grace period is over; returns how many. */
    fun runDue(now: Instant = Instant.now()): Int =
        deletions
            .findTop50ByStatusAndScheduledForBeforeOrderByScheduledForAsc(AccountDeletionStatus.PENDING, now)
            .count { deletion ->
                runCatching { erase(requireNotNull(deletion.id)) }
                    .onFailure { log.error("Could not finish account deletion {}", deletion.id, it) }
                    .isSuccess
            }

    private fun erase(deletionId: UUID) {
        val deletion = deletions.findById(deletionId).orElseThrow()
        val closedNames = deletion.tenants().mapNotNull { closeOrganization(it, deletion.userId, deletion.language) }
        val email = deletion.email
        transactions.executeWithoutResult {
            val row = deletions.findById(deletionId).orElseThrow()
            val user = users.findById(row.userId).orElse(null)
            if (user != null) {
                memberships.deleteAll(memberships.findByUserIdOrderByCreatedAtDesc(row.userId))
                user.email = "deleted+${row.userId}@deleted.invalid"
                user.fullName = null
                user.passwordHash = null
                user.emailVerified = false
                user.marketingConsent.granted = false
                user.passwordChangedAt = Instant.now()
                users.save(user)
            }
            row.status = AccountDeletionStatus.DONE
            row.finishedAt = Instant.now()
            row.email = null
        }
        if (email != null) {
            events.publishEvent(
                AccountDeletionNotice(
                    kind = AccountDeletionNotice.KIND_COMPLETED,
                    email = email,
                    language = deletion.language,
                    organizations = closedNames,
                ),
            )
        }
    }

    /**
     * Erases one organization: every module drops the personal data it holds for
     * it, then the organization keeps only what invoices need. Its other members
     * lose access and are told. Returns its name, or null when it was already closed.
     */
    private fun closeOrganization(
        tenantId: String,
        ownerId: UUID,
        language: String,
    ): String? {
        val tenant = tenants.findById(UUID.fromString(tenantId)).orElse(null) ?: return null
        if (tenant.status == TenantStatus.CLOSED) return null
        val name = tenant.branding?.displayName ?: tenant.name
        val members = memberships.findByTenantIdOrderByCreatedAtAsc(tenantId)
        val otherMemberEmails = members.filter { it.userId != ownerId }.mapNotNull { users.findById(it.userId).orElse(null)?.email }
        TenantContext.withTenant(tenantId) {
            transactions.executeWithoutResult {
                events.publishEvent(OrganizationClosed(tenantId))
                val row = tenants.findById(UUID.fromString(tenantId)).orElseThrow()
                row.status = TenantStatus.CLOSED
                row.contactEmail = "closed+$tenantId@deleted.invalid"
                row.username = null
                row.branding = null
                row.paymentMethods = null
                tenants.save(row)
                memberships.deleteAll(memberships.findByTenantIdOrderByCreatedAtAsc(tenantId))
            }
        }
        tenantGate.invalidate(tenantId)
        otherMemberEmails.forEach { email ->
            events.publishEvent(
                AccountDeletionNotice(
                    kind = AccountDeletionNotice.KIND_ORGANIZATION_CLOSED,
                    email = email,
                    language = language,
                    organizations = listOf(name),
                ),
            )
        }
        return name
    }

    private fun soleOwned(userId: UUID): List<SoleOwnedOrganization> =
        memberships
            .findByUserIdOrderByCreatedAtDesc(userId)
            .filter { it.role == OrganizerRole.OWNER }
            .filter { membership ->
                memberships
                    .findByTenantIdOrderByCreatedAtAsc(membership.tenantId)
                    .none { it.role == OrganizerRole.OWNER && it.userId != userId }
            }.mapNotNull { membership ->
                tenants.findById(UUID.fromString(membership.tenantId)).orElse(null)?.let {
                    SoleOwnedOrganization(id = membership.tenantId, name = it.branding?.displayName ?: it.name)
                }
            }

    private fun confirmIdentity(
        user: OrganizerUser,
        password: String?,
        googleIdToken: String?,
    ) {
        val hash = user.passwordHash
        val confirmed =
            if (hash != null) {
                !password.isNullOrEmpty() && passwordEncoder.matches(password, hash)
            } else {
                !googleIdToken.isNullOrBlank() &&
                    googleVerifier.isConfigured() &&
                    runCatching { googleVerifier.verify(googleIdToken) }.getOrNull()?.let {
                        it.emailVerified && it.email.equals(user.email, ignoreCase = true)
                    } == true
            }
        if (!confirmed) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Confirm it is you to delete your account")
    }

    private fun setStatus(
        tenantId: String,
        status: TenantStatus,
    ) {
        val tenant = tenants.findById(UUID.fromString(tenantId)).orElse(null) ?: return
        if (tenant.status == TenantStatus.CLOSED) return
        tenant.status = status
        tenants.save(tenant)
        tenantGate.invalidate(tenantId)
    }

    private fun requireUser(userId: UUID): OrganizerUser =
        users.findById(userId).orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user") }

    private fun link(
        language: String,
        path: String,
    ): String {
        val prefix = if (language == MessageLanguage.FRENCH) "" else "/$language"
        return "${accountProperties.appBaseUrl}$prefix$path"
    }
}
