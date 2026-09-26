package com.jiku.backoffice.internal

import com.jiku.shared.OpsAlert
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Organizer feedback (JIKU-133). Ratings are asked once per moment and at most
 * once per [FeedbackProperties.promptInterval] per person, so the prompt never
 * becomes noise. A complaint alerts the operating team the moment it arrives.
 */
@Service
class FeedbackService(
    private val feedback: FeedbackRepository,
    private val properties: FeedbackProperties,
    private val tenants: TenantModuleApi,
    private val events: ApplicationEventPublisher,
    private val audit: AdminAuditService,
) {
    @Transactional
    fun submit(
        userId: String,
        tenantId: String?,
        request: FeedbackRequest,
    ): FeedbackAck {
        val kind = parseMessageKind(request.kind)
        val saved =
            feedback.save(
                Feedback(kind = kind, userId = userId, tenantId = tenantId).apply {
                    message = request.message.trim()
                    page = request.page?.trim()?.ifBlank { null }
                    contactEmail = request.contactEmail?.trim()?.ifBlank { null }
                    language = request.language?.trim()?.ifBlank { null }
                },
            )
        if (kind == FeedbackKind.COMPLAINT) {
            events.publishEvent(
                OpsAlert(
                    subject = "New complaint from ${organizationName(tenantId)}",
                    message = "${saved.message}\n\nReply to: ${saved.contactEmail ?: "-"} · Page: ${saved.page ?: "-"}",
                ),
            )
        }
        return FeedbackAck(requireNotNull(saved.id))
    }

    /** Records (or updates) a person's rating of a moment; a null score is a dismissed prompt. */
    @Transactional
    fun rate(
        userId: String,
        tenantId: String?,
        request: RatingRequest,
    ): FeedbackAck {
        val rating =
            feedback.findRating(userId, request.moment)
                ?: Feedback(kind = FeedbackKind.RATING, userId = userId, tenantId = tenantId).apply { moment = request.moment }
        rating.score = request.score
        rating.message = request.comment?.trim()?.ifBlank { null }
        rating.page = request.page?.trim()?.ifBlank { null }
        rating.updatedAt = Instant.now()
        return FeedbackAck(requireNotNull(feedback.save(rating).id))
    }

    /** Whether to ask this person about [moment] now. */
    @Transactional(readOnly = true)
    fun shouldPrompt(
        userId: String,
        moment: String,
    ): PromptDecision {
        if (feedback.findRating(userId, moment) != null) return PromptDecision(show = false)
        val since = Instant.now().minus(properties.promptInterval)
        return PromptDecision(show = !feedback.existsByKindAndUserIdAndCreatedAtGreaterThanEqual(FeedbackKind.RATING, userId, since))
    }

    @Transactional(readOnly = true)
    fun list(
        kind: String?,
        status: String?,
        page: Int,
        size: Int,
    ): FeedbackPage {
        val kindFilter = kind?.takeIf { it.isNotBlank() }?.let { parseKind(it) }
        val statusFilter = status?.takeIf { it.isNotBlank() }?.let { parseStatus(it) }
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = feedback.findAll(matching(kindFilter, statusFilter), pageable)
        val names =
            result.content
                .mapNotNull { it.tenantId }
                .distinct()
                .associateWith { organizationName(it) }
        return FeedbackPage(
            items = result.content.map { it.toView(names[it.tenantId]) },
            total = result.totalElements,
            page = result.number,
            size = result.size,
        )
    }

    @Transactional
    fun updateStatus(
        id: UUID,
        request: FeedbackStatusRequest,
    ): FeedbackView {
        val entry = feedback.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback not found") }
        entry.status = parseStatus(request.status)
        request.note?.let { entry.adminNote = it.trim().ifBlank { null } }
        entry.updatedAt = Instant.now()
        audit.record(action = "FEEDBACK_STATUS", target = "feedback:$id", note = entry.status.name)
        return feedback.save(entry).toView(entry.tenantId?.let { organizationName(it) })
    }

    /** Average score and response count per moment over the last [days] days. */
    @Transactional(readOnly = true)
    fun ratingSummary(days: Int): List<RatingSummary> {
        val since = Instant.now().minusSeconds(days.coerceIn(1, 365).toLong() * 86_400)
        return feedback
            .findByKindAndCreatedAtGreaterThanEqual(FeedbackKind.RATING, since)
            .groupBy { requireNotNull(it.moment) }
            .map { (moment, ratings) ->
                val scored = ratings.mapNotNull { it.score }
                RatingSummary(
                    moment = moment,
                    responses = scored.size,
                    dismissed = ratings.size - scored.size,
                    average = if (scored.isEmpty()) null else scored.average(),
                )
            }.sortedByDescending { it.responses }
    }

    private fun organizationName(tenantId: String?): String =
        tenantId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { tenants.findTenant(it)?.displayName }
            ?: "-"

    private fun parseMessageKind(raw: String): FeedbackKind =
        parseKind(raw).takeIf { it in FeedbackKind.MESSAGES }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown feedback kind: $raw")

    private fun parseKind(raw: String): FeedbackKind =
        runCatching { FeedbackKind.valueOf(raw.uppercase()) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown feedback kind: $raw") }

    private fun parseStatus(raw: String): FeedbackStatus =
        runCatching { FeedbackStatus.valueOf(raw.uppercase()) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown feedback status: $raw") }

    private fun Feedback.toView(organizationName: String?) =
        FeedbackView(
            id = requireNotNull(id),
            kind = kind.name,
            moment = moment,
            score = score,
            message = message,
            page = page,
            contactEmail = contactEmail,
            language = language,
            tenantId = tenantId,
            organizationName = organizationName,
            status = status.name,
            adminNote = adminNote,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

private fun matching(
    kind: FeedbackKind?,
    status: FeedbackStatus?,
): Specification<Feedback> =
    Specification { root, _, builder ->
        builder.and(
            *listOfNotNull(
                kind?.let { builder.equal(root.get<FeedbackKind>("kind"), it) },
                status?.let { builder.equal(root.get<FeedbackStatus>("status"), it) },
            ).toTypedArray(),
        )
    }

interface FeedbackRepository :
    JpaRepository<Feedback, UUID>,
    JpaSpecificationExecutor<Feedback> {
    fun findByKindAndUserIdAndMoment(
        kind: FeedbackKind,
        userId: String,
        moment: String,
    ): Feedback?

    fun existsByKindAndUserIdAndCreatedAtGreaterThanEqual(
        kind: FeedbackKind,
        userId: String,
        since: Instant,
    ): Boolean

    fun findByKindAndCreatedAtGreaterThanEqual(
        kind: FeedbackKind,
        since: Instant,
    ): List<Feedback>
}

private fun FeedbackRepository.findRating(
    userId: String,
    moment: String,
): Feedback? = findByKindAndUserIdAndMoment(FeedbackKind.RATING, userId, moment)
