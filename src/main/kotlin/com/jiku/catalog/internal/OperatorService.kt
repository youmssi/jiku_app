package com.jiku.catalog.internal

import com.jiku.catalog.OperatorAction
import com.jiku.shared.RandomCode
import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/**
 * The organization's operators (JIKU-116): the organizer defines who works
 * where and doing what, shares each operator one link, and revokes it at once
 * when needed. Also serves the per-event door links (JIKU-23) and per-service
 * counter links (JIKU-88) that operators replaced, so the screens built on them
 * keep working.
 */
@SpringService
class OperatorService(
    private val operators: OperatorRepository,
    private val events: EventRepository,
    private val services: ServiceRepository,
    private val tokens: OperatorTokenService,
    private val properties: OperatorLinkProperties,
    private val tenantAccessGate: TenantAccessGate,
) {
    @Transactional(readOnly = true)
    fun team(): OperatorTeamView {
        val team = operators.findAllWithScope()
        val names = scopeNames(team)
        return OperatorTeamView(operators = team.map { it.toView(names) }, billableSeats = team.count { it.billable })
    }

    @Transactional
    fun create(request: OperatorRequest): OperatorView {
        val operator = Operator(label = request.label.trim())
        operator.code = newCode()
        operator.define(request)
        return operators.save(operator).let { it.toView(scopeNames(listOf(it))) }
    }

    @Transactional
    fun update(
        operatorId: UUID,
        request: OperatorRequest,
    ): OperatorView {
        val operator = load(operatorId)
        operator.label = request.label.trim()
        operator.define(request)
        return operators.save(operator).let { it.toView(scopeNames(listOf(it))) }
    }

    @Transactional
    fun revoke(operatorId: UUID): OperatorView {
        val operator = load(operatorId)
        operator.revoke()
        return operators.save(operator).let { it.toView(scopeNames(listOf(it))) }
    }

    /**
     * Resolves a short code into a fresh signed link, or 404. The code is only an
     * entry point, never itself a bearer of API calls. [pinService] pins the link
     * to the operator's single service, for the counter links (JIKU-88) that
     * expect one.
     */
    fun resolveCode(
        code: String,
        pinService: Boolean = false,
    ): OperatorLinkResolution {
        val row = operators.findRowByCode(code).firstOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid")
        val operatorId = UUID.fromString(row[0].toString())
        val tenantId = row[1].toString()
        if (row[2] as Boolean) throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link has been revoked")
        if (tenantAccessGate.isSuspended(tenantId)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is no longer available")
        val pinnedServiceId = if (pinService) singleServiceOf(operatorId, tenantId) else null
        return OperatorLinkResolution(tokens.issue(operatorId, tenantId, pinnedServiceId = pinnedServiceId))
    }

    /** What the operator's console opens on; [operator] comes from [OperatorGate]. */
    @Transactional(readOnly = true)
    fun console(operator: Operator): OperatorConsoleView =
        OperatorConsoleView(
            label = operator.label,
            actions = operator.actions,
            events =
                events
                    .findAllById(operator.eventIds)
                    .filter { it.status != EventStatus.DRAFT }
                    .sortedWith(compareBy(nullsLast()) { it.startDateTime })
                    .map {
                        OperatorConsoleEvent(
                            id = requireNotNull(it.id),
                            name = it.name,
                            status = it.status.name,
                            startDateTime = it.startDateTime,
                            timezone = it.timezone,
                            location = it.location,
                        )
                    },
            services =
                services
                    .findAllById(operator.serviceIds)
                    .sortedBy { it.name }
                    .map { OperatorConsoleService(id = requireNotNull(it.id), name = it.name, timezone = it.timezone) },
        )

    @Transactional
    fun createDoorLink(
        eventId: UUID,
        label: String?,
    ): ValidatorResponse {
        requirePublished(listOf(eventId))
        val operator = Operator(label = label?.trim()?.ifBlank { null } ?: DEFAULT_DOOR_LABEL)
        operator.code = newCode()
        operator.eventIds.add(eventId)
        operator.actions = setOf(OperatorAction.CHECK_IN, OperatorAction.COLLECT)
        return operators.save(operator).toDoorLink(eventId)
    }

    @Transactional(readOnly = true)
    fun doorLinks(eventId: UUID): List<ValidatorResponse> {
        events.findById(eventId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found") }
        return operators.findByEvent(eventId).map { it.toDoorLink(eventId) }
    }

    /** Revokes the operator behind a door link, wherever else they work. */
    @Transactional
    fun revokeDoorLink(
        eventId: UUID,
        operatorId: UUID,
    ): ValidatorResponse {
        val operator = load(operatorId)
        if (eventId !in operator.eventIds) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Validator link not found")
        operator.revoke()
        return operators.save(operator).toDoorLink(eventId)
    }

    @Transactional
    fun createCounterLink(
        serviceId: UUID,
        label: String,
    ): ServiceStaffCreatedResponse {
        requireServices(listOf(serviceId))
        val operator = Operator(label = label.trim())
        operator.code = newCode()
        operator.serviceIds.add(serviceId)
        operator.actions = setOf(OperatorAction.QUEUE, OperatorAction.COLLECT)
        val saved = operators.save(operator)
        return ServiceStaffCreatedResponse(
            id = requireNotNull(saved.id),
            label = saved.label,
            token = tokens.issue(requireNotNull(saved.id), currentTenant(), pinnedServiceId = serviceId),
            code = saved.code,
            createdAt = saved.createdAt,
        )
    }

    @Transactional(readOnly = true)
    fun counterLinks(serviceId: UUID): List<ServiceStaffView> {
        requireServices(listOf(serviceId))
        return operators.findByService(serviceId).map { it.toCounterLink(serviceId) }
    }

    /** Revokes the operator behind a counter link, wherever else they work. */
    @Transactional
    fun revokeCounterLink(
        serviceId: UUID,
        operatorId: UUID,
    ) {
        requireServices(listOf(serviceId))
        val operator = load(operatorId)
        if (serviceId !in operator.serviceIds) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Staff link not found: $operatorId")
        operator.revoke()
        operators.save(operator)
    }

    private fun Operator.define(request: OperatorRequest) {
        if (request.eventIds.isEmpty() && request.serviceIds.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the operator at least one event or service")
        }
        requirePublished(request.eventIds - eventIds)
        requireServices(request.serviceIds)
        eventIds.retainAll(request.eventIds)
        eventIds.addAll(request.eventIds)
        serviceIds.retainAll(request.serviceIds)
        serviceIds.addAll(request.serviceIds)
        actions = request.actions
    }

    /** Events newly put in scope must exist and be published: a draft has no door to staff. */
    private fun requirePublished(eventIds: Collection<UUID>) {
        if (eventIds.isEmpty()) return
        val found = events.findAllById(eventIds)
        if (found.size != eventIds.toSet().size) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        if (found.any { it.status != EventStatus.PUBLISHED }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Publish the event before giving an operator access to it")
        }
    }

    private fun requireServices(serviceIds: Collection<UUID>) {
        if (serviceIds.isEmpty()) return
        if (services.findAllById(serviceIds).size != serviceIds.toSet().size) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found")
        }
    }

    private fun singleServiceOf(
        operatorId: UUID,
        tenantId: String,
    ): UUID {
        TenantContext.set(tenantId)
        try {
            val serviceIds = operators.findWithScopeById(operatorId)?.serviceIds.orEmpty()
            return serviceIds.singleOrNull()
                ?: throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This link covers several services; open it from the operator console",
                )
        } finally {
            TenantContext.clear()
        }
    }

    private fun load(operatorId: UUID): Operator =
        operators.findWithScopeById(operatorId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Operator not found: $operatorId")

    /** A code not yet used is guaranteed by the unique index: retry on the astronomically rare collision. */
    private fun newCode(): String {
        var code: String
        do {
            code = RandomCode.generate(CODE_LENGTH)
        } while (operators.findRowByCode(code).isNotEmpty())
        return code
    }

    private fun currentTenant(): String = TenantContext.get() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant")

    private fun Operator.toView(names: ScopeNames): OperatorView =
        OperatorView(
            id = requireNotNull(id),
            label = label,
            code = code,
            link = code?.let { "${properties.appBaseUrl}/operator/$it" },
            events = eventIds.map { OperatorScopeItem(it, names.events[it].orEmpty()) }.sortedBy { it.name },
            services = serviceIds.map { OperatorScopeItem(it, names.services[it].orEmpty()) }.sortedBy { it.name },
            actions = actions,
            billable = billable,
            revoked = revoked,
            createdAt = createdAt,
            revokedAt = revokedAt,
        )

    private fun Operator.toDoorLink(eventId: UUID): ValidatorResponse =
        ValidatorResponse(
            id = requireNotNull(id),
            label = label,
            link = "${properties.appBaseUrl}/checkin/${tokens.issue(requireNotNull(id), currentTenant(), pinnedEventId = eventId)}",
            revoked = revoked,
            createdAt = createdAt,
            revokedAt = revokedAt,
        )

    private fun Operator.toCounterLink(serviceId: UUID): ServiceStaffView =
        ServiceStaffView(
            id = requireNotNull(id),
            serviceId = serviceId,
            label = label,
            revoked = revoked,
            code = code,
            createdAt = createdAt,
            revokedAt = revokedAt,
        )

    /** Names of every event and service [team] reaches, loaded in two queries. */
    private fun scopeNames(team: List<Operator>): ScopeNames =
        ScopeNames(
            events = events.findAllById(team.flatMap { it.eventIds }.toSet()).associate { requireNotNull(it.id) to it.name },
            services = services.findAllById(team.flatMap { it.serviceIds }.toSet()).associate { requireNotNull(it.id) to it.name },
        )

    private class ScopeNames(
        val events: Map<UUID, String>,
        val services: Map<UUID, String>,
    )

    private companion object {
        const val CODE_LENGTH = 10
        const val DEFAULT_DOOR_LABEL = "Entrance"
    }
}
