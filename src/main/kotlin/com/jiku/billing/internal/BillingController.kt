package com.jiku.billing.internal

import com.jiku.billing.BillingAllowance
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Organizer-facing read of an event's usage and allowance (JIKU-32). Tenant is
 * bound from the JWT and the record is tenant-scoped, so an organizer only ever
 * sees their own event's usage.
 */
@RestController
@RequestMapping("/events/{eventId}/usage")
@PreAuthorize("hasRole('ORGANIZER')")
class BillingController(
    private val usageService: UsageService,
) {
    @GetMapping
    fun usage(
        @PathVariable eventId: UUID,
    ): BillingAllowance = usageService.allowance(eventId)
}
