package com.jiku.tenant.internal

import com.jiku.shared.MessageLanguage
import com.jiku.shared.SecurityNotice
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.CredentialRecord
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.api.PublicKeyCredential
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement
import org.springframework.security.web.webauthn.api.UserVerificationRequirement
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Passkey settings (JIKU-318): the relying party is the site people sign in on,
 * identified by its domain, and the origins its pages are served from.
 */
@ConfigurationProperties(prefix = "auth.passkeys")
data class PasskeyProperties(
    val rpId: String = "",
    val rpName: String = "Jikū",
    val origins: Set<String> = setOf("http://localhost:3000"),
    val ceremonyTtl: Duration = Duration.ofMinutes(5),
    val maxPerAccount: Int = 10,
)

/** One step of a passkey ceremony: what the browser passes to `navigator.credentials`. */
data class PasskeyCeremony(
    val ceremonyId: String,
    /** The WebAuthn options, in the JSON form browsers parse. */
    val options: JsonNode,
)

data class PasskeyView(
    val id: String,
    val label: String,
    val createdAt: Instant?,
    val lastUsedAt: Instant?,
)

/**
 * Passkeys for organizer accounts (JIKU-318), with Spring Security's WebAuthn
 * relying party and its JDBC repositories. The sign-in itself stays the
 * application's: a verified passkey yields the account, and the caller issues
 * the session as for any other sign-in.
 *
 * Each ceremony's options are kept here between the two calls and used once.
 * They live in memory for a few minutes, like the API's default rate limits:
 * a restart only asks the person to try again.
 */
@Service
class PasskeyService(
    jdbc: JdbcTemplate,
    private val users: OrganizerUserRepository,
    private val properties: PasskeyProperties,
    private val accountProperties: AccountProperties,
    private val events: ApplicationEventPublisher,
) {
    private val userEntities = JdbcPublicKeyCredentialUserEntityRepository(jdbc)
    private val credentials = JdbcUserCredentialRepository(jdbc)
    private val relyingParty =
        Webauthn4JRelyingPartyOperations(
            userEntities,
            credentials,
            PublicKeyCredentialRpEntity
                .builder()
                .id(relyingPartyId())
                .name(properties.rpName)
                .build(),
            properties.origins,
        ).apply {
            // A passkey that holds the account itself (discoverable) and unlocks
            // with the device's fingerprint, face or PIN: possession and
            // verification together, so it counts as two factors.
            setCustomizeCreationOptions { builder ->
                builder
                    .authenticatorSelection(
                        AuthenticatorSelectionCriteria
                            .builder()
                            .residentKey(ResidentKeyRequirement.REQUIRED)
                            .userVerification(UserVerificationRequirement.REQUIRED)
                            .build(),
                    ).timeout(properties.ceremonyTtl)
            }
            setCustomizeRequestOptions { builder ->
                builder.userVerification(UserVerificationRequirement.REQUIRED).timeout(properties.ceremonyTtl)
            }
        }
    private val mapper = JsonMapper.builder().addModule(WebauthnJacksonModule()).build()
    private val registrations = ConcurrentHashMap<String, Pending<PublicKeyCredentialCreationOptions>>()
    private val signIns = ConcurrentHashMap<String, Pending<PublicKeyCredentialRequestOptions>>()

    private data class Pending<T>(
        val userId: UUID?,
        val options: T,
        val expiresAt: Instant,
    )

    /** The configured domain, or the host of the first origin people sign in from. */
    private fun relyingPartyId(): String =
        properties.rpId.ifBlank {
            requireNotNull(properties.origins.firstOrNull()?.let { java.net.URI(it).host }) {
                "auth.passkeys.origins must name the site people sign in on"
            }
        }

    @Transactional
    fun startRegistration(userId: UUID): PasskeyCeremony {
        val user = requireUser(userId)
        if (credentials.findByUserId(entityOf(user).id).size >= properties.maxPerAccount) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This account already has ${properties.maxPerAccount} passkeys")
        }
        val options =
            relyingParty.createPublicKeyCredentialCreationOptions(
                ImmutablePublicKeyCredentialCreationOptionsRequest(asAuthentication(userId)),
            )
        return PasskeyCeremony(keep(registrations, userId, options), mapper.valueToTree(options))
    }

    @Transactional
    fun finishRegistration(
        userId: UUID,
        ceremonyId: String,
        credential: JsonNode,
        label: String,
    ): PasskeyView {
        val user = requireUser(userId)
        val options = take(registrations, ceremonyId, userId)
        val publicKey =
            runCatching {
                mapper.treeToValue(credential, object : TypeReference<PublicKeyCredential<AuthenticatorAttestationResponse>>() {})
            }.getOrElse { throw invalid() }
        val record =
            runCatching {
                relyingParty.registerCredential(
                    ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(publicKey, label.trim().take(LABEL_LENGTH))),
                )
            }.getOrElse { throw invalid() }
        notify(user, SecurityNotice.KIND_PASSKEY_ADDED)
        return viewOf(record)
    }

    fun startSignIn(): PasskeyCeremony {
        val options = relyingParty.createCredentialRequestOptions(ImmutablePublicKeyCredentialRequestOptionsRequest(null))
        return PasskeyCeremony(keep(signIns, null, options), mapper.valueToTree(options))
    }

    /** The account a verified passkey belongs to; the caller opens the session. */
    @Transactional
    fun finishSignIn(
        ceremonyId: String,
        credential: JsonNode,
    ): OrganizerUser {
        val options = take(signIns, ceremonyId, null)
        val assertion =
            runCatching {
                mapper.treeToValue(credential, object : TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>() {})
            }.getOrElse { throw invalid() }
        val entity =
            runCatching {
                relyingParty.authenticate(
                    RelyingPartyAuthenticationRequest(options, assertion),
                )
            }.getOrElse { throw invalid() }
        val userId = runCatching { UUID.fromString(entity.name) }.getOrElse { throw invalid() }
        return users.findById(userId).orElseThrow { invalid() }
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<PasskeyView> {
        val entity = userEntities.findByUsername(userId.toString()) ?: return emptyList()
        return credentials.findByUserId(entity.id).sortedByDescending { it.created }.map(::viewOf)
    }

    @Transactional
    fun rename(
        userId: UUID,
        passkeyId: String,
        label: String,
    ): PasskeyView {
        val record = owned(userId, passkeyId)
        val renamed = ImmutableCredentialRecord.fromCredentialRecord(record).label(label.trim().take(LABEL_LENGTH)).build()
        credentials.save(renamed)
        return viewOf(renamed)
    }

    @Transactional
    fun delete(
        userId: UUID,
        passkeyId: String,
    ) {
        credentials.delete(owned(userId, passkeyId).credentialId)
    }

    /** Removes every passkey of an account being erased (JIKU-316). */
    @Transactional
    fun forget(userId: UUID) {
        val entity = userEntities.findByUsername(userId.toString()) ?: return
        credentials.findByUserId(entity.id).forEach { credentials.delete(it.credentialId) }
        userEntities.delete(entity.id)
    }

    /**
     * The WebAuthn user handle of an account: a random id kept for it, so the
     * authenticator never stores the account id itself; the email is what the
     * device shows when it offers the passkey.
     */
    private fun entityOf(user: OrganizerUser) =
        userEntities.findByUsername(requireNotNull(user.id).toString())
            ?: ImmutablePublicKeyCredentialUserEntity
                .builder()
                .name(requireNotNull(user.id).toString())
                .id(Bytes.random())
                .displayName(user.email)
                .build()
                .also(userEntities::save)

    private fun owned(
        userId: UUID,
        passkeyId: String,
    ): CredentialRecord {
        val entity = userEntities.findByUsername(userId.toString()) ?: throw notFound()
        val id = runCatching { Bytes.fromBase64(passkeyId) }.getOrElse { throw notFound() }
        return credentials.findByCredentialId(id)?.takeIf { it.userEntityUserId == entity.id } ?: throw notFound()
    }

    private fun <T> keep(
        store: ConcurrentHashMap<String, Pending<T>>,
        userId: UUID?,
        options: T,
    ): String {
        val now = Instant.now()
        store.entries.removeIf { it.value.expiresAt.isBefore(now) }
        val id = UUID.randomUUID().toString()
        store[id] = Pending(userId, options, now.plus(properties.ceremonyTtl))
        return id
    }

    private fun <T> take(
        store: ConcurrentHashMap<String, Pending<T>>,
        ceremonyId: String,
        userId: UUID?,
    ): T {
        val pending = store.remove(ceremonyId) ?: throw invalid()
        if (pending.expiresAt.isBefore(Instant.now()) || pending.userId != userId) throw invalid()
        return pending.options
    }

    private fun viewOf(record: CredentialRecord) =
        PasskeyView(
            id = record.credentialId.toBase64UrlString(),
            label = record.label,
            createdAt = record.created,
            lastUsedAt = record.lastUsed,
        )

    private fun notify(
        user: OrganizerUser,
        kind: String,
    ) {
        val language = MessageLanguage.normalize(LocaleContextHolder.getLocale().toLanguageTag())
        val prefix = if (language == MessageLanguage.FRENCH) "" else "/$language"
        events.publishEvent(
            SecurityNotice(
                kind = kind,
                email = user.email,
                language = language,
                actionUrl = "${accountProperties.appBaseUrl}$prefix/settings?tab=security",
            ),
        )
    }

    private fun asAuthentication(userId: UUID) = UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, emptyList())

    private fun requireUser(userId: UUID): OrganizerUser =
        users.findById(userId).orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user") }

    private fun invalid() = ResponseStatusException(HttpStatus.UNAUTHORIZED, "This passkey could not be verified. Try again.")

    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Passkey not found")

    private companion object {
        const val LABEL_LENGTH = 100
    }
}
