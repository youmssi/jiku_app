package com.jiku.checkin.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.shared.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Manages validator access links: an organizer mints, lists and revokes them for
 * their events; the validator-facing check-in flow resolves a link to its active
 * label. Links are tenant-scoped and never grant access beyond check-in for their
 * one event.
 */
@Service
class ValidatorService(
    private val validators: ValidatorRepository,
    private val tokenService: ValidatorTokenService,
    private val events: EventModuleApi,
    private val properties: ValidatorLinkProperties,
) {
    @Transactional
    fun generate(
        eventId: UUID,
        label: String?,
    ): ValidatorResponse {
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        val validator = validators.save(Validator(eventId = eventId, label = resolveLabel(label)))
        return validator.toResponse()
    }

    @Transactional(readOnly = true)
    fun list(eventId: UUID): List<ValidatorResponse> = validators.findByEventIdOrderByCreatedAtAsc(eventId).map { it.toResponse() }

    @Transactional
    fun revoke(
        eventId: UUID,
        validatorId: UUID,
    ): ValidatorResponse {
        val validator = load(validatorId, eventId)
        if (!validator.revoked) {
            validator.revoked = true
            validator.revokedAt = Instant.now()
            validators.save(validator)
        }
        return validator.toResponse()
    }

    /**
     * Resolves a link's validator id and event to its active label, rejecting a
     * revoked link, an unknown one, or one whose event no longer matches the token.
     */
    @Transactional(readOnly = true)
    fun resolveActiveLabel(
        validatorId: UUID,
        eventId: UUID,
    ): String {
        val validator =
            validators.findById(validatorId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "This check-in link is invalid")
            }
        if (validator.eventId != eventId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This check-in link is invalid")
        }
        if (validator.revoked) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "This check-in link has been revoked")
        }
        return validator.label
    }

    private fun load(
        validatorId: UUID,
        eventId: UUID,
    ): Validator {
        val validator =
            validators.findById(validatorId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Validator link not found")
            }
        if (validator.eventId != eventId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Validator link not found")
        }
        return validator
    }

    private fun resolveLabel(label: String?): String = label?.trim()?.ifBlank { null } ?: DEFAULT_LABEL

    private fun Validator.toResponse(): ValidatorResponse {
        val id = requireNotNull(id)
        val tenantId = requireNotNull(TenantContext.get())
        val token = tokenService.issue(id, eventId, tenantId)
        return ValidatorResponse(
            id = id,
            label = label,
            link = "${properties.appBaseUrl}/checkin/$token",
            revoked = revoked,
            createdAt = createdAt,
            revokedAt = revokedAt,
        )
    }

    private companion object {
        const val DEFAULT_LABEL = "Entrance"
    }
}
