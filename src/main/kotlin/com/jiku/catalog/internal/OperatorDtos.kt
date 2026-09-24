package com.jiku.catalog.internal

import com.jiku.catalog.OperatorAction
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** An operator as the organizer defines it: who, where, and what they may do (JIKU-116). */
data class OperatorRequest(
    @field:NotBlank @field:Size(max = 80) val label: String,
    val eventIds: Set<UUID> = emptySet(),
    val serviceIds: Set<UUID> = emptySet(),
    @field:NotEmpty val actions: Set<OperatorAction>,
)

data class OperatorScopeItem(
    val id: UUID,
    val name: String,
)

/**
 * An operator as the organizer sees it. [link] opens the operator console through
 * the short [code]; it can be copied again at any time. [billable] operators — a
 * service in scope, not revoked — take a seat of the subscription.
 */
data class OperatorView(
    val id: UUID,
    val label: String,
    val code: String?,
    val link: String?,
    val events: List<OperatorScopeItem>,
    val services: List<OperatorScopeItem>,
    val actions: Set<OperatorAction>,
    val billable: Boolean,
    val revoked: Boolean,
    val createdAt: Instant,
    val revokedAt: Instant?,
)

data class OperatorTeamView(
    val operators: List<OperatorView>,
    val billableSeats: Int,
)

/** A resolved short code: the signed link the console works with. */
data class OperatorLinkResolution(
    val token: String,
)

/** What an operator's console opens on: who they are, what they may do, and where. */
data class OperatorConsoleView(
    val label: String,
    val actions: Set<OperatorAction>,
    val events: List<OperatorConsoleEvent>,
    val services: List<OperatorConsoleService>,
)

/** An event in an operator's scope; times are UTC instants rendered in [timezone]. */
data class OperatorConsoleEvent(
    val id: UUID,
    val name: String,
    val status: String,
    val startDateTime: Instant?,
    val timezone: String,
    val location: String?,
)

data class OperatorConsoleService(
    val id: UUID,
    val name: String,
    val timezone: String,
)

/** Organizer request to mint a door link for one event (JIKU-23), optionally labeled. */
data class CreateValidatorRequest(
    @field:Size(max = 80)
    val label: String? = null,
)

/**
 * A door link for one event as the organizer sees it. [link] is the shareable
 * URL; a revoked link is returned for transparency but no longer opens.
 */
data class ValidatorResponse(
    val id: UUID,
    val label: String,
    val link: String,
    val revoked: Boolean,
    val createdAt: Instant,
    val revokedAt: Instant?,
)
