package com.jiku.tenant.internal

import com.jiku.shared.TenantContext
import jakarta.validation.Valid
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.UUID

/**
 * Bounds of the time an unpaid ticket order keeps its places (JIKU-177). The
 * organization picks its own value between [min] and [max], to match how fast
 * it answers payment declarations; [default] applies until it does.
 */
@ConfigurationProperties(prefix = "sales.order-hold")
data class OrderHoldProperties(
    val default: Duration = Duration.ofMinutes(30),
    val min: Duration = Duration.ofMinutes(10),
    val max: Duration = Duration.ofHours(72),
)

data class SalesSettingsView(
    /** The organization's choice, or null while it keeps the default. */
    val orderHoldMinutes: Int?,
    /** What applies now. */
    val effectiveOrderHoldMinutes: Int,
    val defaultOrderHoldMinutes: Int,
    val minOrderHoldMinutes: Int,
    val maxOrderHoldMinutes: Int,
)

/** Null restores the platform default. */
data class UpdateSalesSettingsRequest(
    val orderHoldMinutes: Int? = null,
)

/**
 * The organization's ticket-sale settings (JIKU-177): for now, how long an
 * unpaid order keeps its places. Managers only, like payment methods.
 */
@RestController
@RequestMapping("/settings/sales")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class SalesSettingsController(
    private val settings: SalesSettingsService,
) {
    @GetMapping
    fun get(): SalesSettingsView = settings.view(currentTenantId())

    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdateSalesSettingsRequest,
    ): SalesSettingsView = settings.update(currentTenantId(), request.orderHoldMinutes)

    private fun currentTenantId(): UUID =
        UUID.fromString(TenantContext.get() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context"))
}

@Service
class SalesSettingsService(
    private val tenants: TenantRepository,
    private val properties: OrderHoldProperties,
) {
    @Transactional(readOnly = true)
    fun orderHold(tenantId: UUID): Duration =
        tenants
            .findById(tenantId)
            .orElse(null)
            ?.orderHoldMinutes
            ?.let { Duration.ofMinutes(it.toLong()) }
            ?: properties.default

    @Transactional(readOnly = true)
    fun view(tenantId: UUID): SalesSettingsView = load(tenantId).toView()

    @Transactional
    fun update(
        tenantId: UUID,
        orderHoldMinutes: Int?,
    ): SalesSettingsView {
        if (orderHoldMinutes != null && orderHoldMinutes !in properties.min.toMinutes()..properties.max.toMinutes()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Choose between ${properties.min.toMinutes()} and ${properties.max.toMinutes()} minutes",
            )
        }
        val tenant = load(tenantId)
        tenant.orderHoldMinutes = orderHoldMinutes
        return tenants.save(tenant).toView()
    }

    private fun load(tenantId: UUID): Tenant =
        tenants.findById(tenantId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found") }

    private fun Tenant.toView(): SalesSettingsView =
        SalesSettingsView(
            orderHoldMinutes = orderHoldMinutes,
            effectiveOrderHoldMinutes = (orderHoldMinutes?.toLong() ?: properties.default.toMinutes()).toInt(),
            defaultOrderHoldMinutes = properties.default.toMinutes().toInt(),
            minOrderHoldMinutes = properties.min.toMinutes().toInt(),
            maxOrderHoldMinutes = properties.max.toMinutes().toInt(),
        )
}
