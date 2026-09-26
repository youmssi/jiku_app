package com.jiku.backoffice.internal

import com.jiku.shared.TenantContext
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * An organizer's feedback (JIKU-133): a message to the platform team, a rating
 * after a key action, and whether the app should ask for one now.
 */
@RestController
@RequestMapping("/feedback")
class FeedbackController(
    private val service: FeedbackService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(
        authentication: Authentication,
        @Valid @RequestBody request: FeedbackRequest,
    ): FeedbackAck = service.submit(authentication.name, TenantContext.get(), request)

    @PostMapping("/ratings")
    @ResponseStatus(HttpStatus.CREATED)
    fun rate(
        authentication: Authentication,
        @Valid @RequestBody request: RatingRequest,
    ): FeedbackAck = service.rate(authentication.name, TenantContext.get(), request)

    @GetMapping("/prompt")
    fun prompt(
        authentication: Authentication,
        @RequestParam @Pattern(regexp = MOMENT_PATTERN) moment: String,
    ): PromptDecision = service.shouldPrompt(authentication.name, moment)
}

/** The admin desk's feedback inbox and rating summary. */
@RestController
@RequestMapping("/admin/feedback")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminFeedbackController(
    private val service: FeedbackService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): FeedbackPage = service.list(kind, status, page, size)

    @PostMapping("/{id}/status")
    fun updateStatus(
        @PathVariable id: UUID,
        @Valid @RequestBody request: FeedbackStatusRequest,
    ): FeedbackView = service.updateStatus(id, request)

    @GetMapping("/ratings")
    fun ratings(
        @RequestParam(defaultValue = "30") days: Int,
    ): List<RatingSummary> = service.ratingSummary(days)
}

const val MOMENT_PATTERN = "^[a-z][a-z_]{0,63}$"

data class FeedbackRequest(
    /** PROBLEM, QUESTION, IDEA or COMPLAINT. */
    @field:NotBlank
    val kind: String,
    @field:NotBlank
    @field:Size(min = 3, max = 4000)
    val message: String,
    @field:Size(max = 255)
    val page: String? = null,
    @field:Email
    @field:Size(max = 255)
    val contactEmail: String? = null,
    @field:Size(max = 8)
    val language: String? = null,
)

data class RatingRequest(
    @field:NotBlank
    @field:Pattern(regexp = MOMENT_PATTERN)
    val moment: String,
    /** 1 to 5; null when the person dismissed the prompt. */
    @field:Min(1)
    @field:Max(5)
    val score: Int? = null,
    @field:Size(max = 1000)
    val comment: String? = null,
    @field:Size(max = 255)
    val page: String? = null,
)

data class FeedbackStatusRequest(
    @field:NotBlank
    val status: String,
    @field:Size(max = 2000)
    val note: String? = null,
)

data class FeedbackAck(
    val id: UUID,
)

data class PromptDecision(
    val show: Boolean,
)

data class FeedbackView(
    val id: UUID,
    val kind: String,
    val moment: String?,
    val score: Int?,
    val message: String?,
    val page: String?,
    val contactEmail: String?,
    val language: String?,
    val tenantId: String?,
    val organizationName: String?,
    val status: String,
    val adminNote: String?,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

data class FeedbackPage(
    val items: List<FeedbackView>,
    val total: Long,
    val page: Int,
    val size: Int,
)

data class RatingSummary(
    val moment: String,
    val responses: Int,
    val dismissed: Int,
    val average: Double?,
)
