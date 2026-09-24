package com.jiku.catalog.internal

import com.jiku.shared.TenantAccessGate
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Resolves a counter-staff link's short code (JIKU-88) into a fresh signed
 * day-line token — the same token [LineStaffController] has always expected.
 * The code is only ever an entry point, never itself a bearer of API calls:
 * this is the one place a code and a JWT meet, so [LineStaffController] and
 * the console it serves stay entirely unaware short links exist.
 */
@RestController
@RequestMapping("/line-codes")
class LineCodeController(
    private val staff: ServiceStaffRepository,
    private val tokens: DayLineTokenService,
    private val tenantAccessGate: TenantAccessGate,
) {
    @GetMapping("/{code}")
    fun resolve(
        @PathVariable code: String,
    ): LineCodeResolution {
        val row =
            staff.findRowByCode(code).firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This counter link is invalid")
        val staffId = UUID.fromString(row[0].toString())
        val serviceId = UUID.fromString(row[1].toString())
        val tenantId = row[2].toString()
        val revoked = row[3] as Boolean
        if (revoked) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This counter link has been revoked")
        }
        if (tenantAccessGate.isSuspended(tenantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This counter link is no longer available")
        }
        return LineCodeResolution(token = tokens.issue(staffId, serviceId, tenantId))
    }
}

data class LineCodeResolution(
    val token: String,
)
