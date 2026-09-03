package com.jiku.backoffice.internal

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Read-only audit trail of back-office actions (JIKU-40), newest first. */
@RestController
@RequestMapping("/admin/audit")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminAuditController(
    private val entries: AdminAuditLogRepository,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) action: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): AuditPage {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 200), Sort.by(Sort.Direction.DESC, "createdAt"))
        val result =
            if (action.isNullOrBlank()) {
                entries.findAll(pageable)
            } else {
                entries.findByAction(action.trim(), pageable)
            }
        return AuditPage(
            entries =
                result.content.map {
                    AuditEntry(
                        id = requireNotNull(it.id),
                        adminId = it.adminId,
                        action = it.action,
                        target = it.target,
                        note = it.note,
                        createdAt = it.createdAt,
                    )
                },
            total = result.totalElements,
            page = result.number,
            size = result.size,
        )
    }
}

data class AuditPage(
    val entries: List<AuditEntry>,
    val total: Long,
    val page: Int,
    val size: Int,
)

data class AuditEntry(
    val id: UUID,
    val adminId: UUID,
    val action: String,
    val target: String,
    val note: String?,
    val createdAt: Instant,
)
