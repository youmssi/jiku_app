package com.jiku.catalog.internal

import com.jiku.shared.BaseTenantEntity
import com.jiku.shared.TenantContext
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/** A choice question asked before the times (JIKU-191). */
@Entity
@Table(name = "service_question")
class ServiceQuestion(
    @Column(name = "service_id", nullable = false, updatable = false)
    val serviceId: UUID,
    @Column(name = "position", nullable = false)
    var position: Int,
    @Column(name = "prompt", nullable = false, length = 140)
    var prompt: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

/** One answer to a [ServiceQuestion]; it may send the client to another service. */
@Entity
@Table(name = "service_question_choice")
class ServiceQuestionChoice(
    @Column(name = "question_id", nullable = false, updatable = false)
    val questionId: UUID,
    @Column(name = "position", nullable = false)
    var position: Int,
    @Column(name = "label", nullable = false, length = 20)
    var label: String,
    @Column(name = "route_service_id")
    var routeServiceId: UUID? = null,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}

/** What a client answered, copied as text when they booked (JIKU-191). */
@Entity
@Table(name = "service_booking_answer")
class ServiceBookingAnswer(
    @Column(name = "service_id", nullable = false, updatable = false)
    val serviceId: UUID,
    @Column(name = "booking_token_hash", nullable = false, updatable = false, length = 64)
    val bookingTokenHash: String,
    @Column(name = "starts_at", nullable = false, updatable = false)
    val startsAt: Instant,
    @Column(name = "position", nullable = false, updatable = false)
    val position: Int,
    @Column(name = "question", nullable = false, updatable = false, length = 140)
    val question: String,
    @Column(name = "answer", nullable = false, updatable = false, length = 20)
    val answer: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}

interface ServiceQuestionRepository : JpaRepository<ServiceQuestion, UUID> {
    fun findByServiceIdOrderByPosition(serviceId: UUID): List<ServiceQuestion>
}

interface ServiceQuestionChoiceRepository : JpaRepository<ServiceQuestionChoice, UUID> {
    fun findByQuestionIdInOrderByPosition(questionIds: Collection<UUID>): List<ServiceQuestionChoice>

    @Modifying
    @Query("DELETE FROM ServiceQuestionChoice c WHERE c.questionId IN :questionIds")
    fun deleteByQuestionIdIn(questionIds: Collection<UUID>)
}

interface ServiceBookingAnswerRepository : JpaRepository<ServiceBookingAnswer, UUID> {
    fun findByBookingTokenHashInOrderByPosition(hashes: Collection<String>): List<ServiceBookingAnswer>

    fun findByServiceIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscPosition(
        serviceId: UUID,
        from: Instant,
        to: Instant,
    ): List<ServiceBookingAnswer>

    @Modifying
    @Query("DELETE FROM ServiceBookingAnswer a WHERE a.bookingTokenHash = :hash")
    fun deleteByBookingTokenHash(hash: String)

    /**
     * Removes the answers of bookings whose reservations are gone (an expired
     * request swept away). Native and across organizations on purpose: it only
     * ever touches rows left without a booking.
     */
    @Modifying
    @Query(
        value =
            "DELETE FROM service_booking_answer a WHERE NOT EXISTS " +
                "(SELECT 1 FROM service_reservation r WHERE r.booking_token_hash = a.booking_token_hash)",
        nativeQuery = true,
    )
    fun deleteOrphans(): Int
}

/** A choice as the organizer edits it; [routeServiceId] sends the client to another of its services. */
data class ServiceQuestionChoiceInput(
    @field:NotBlank @field:Size(max = MAX_LABEL) val label: String,
    val routeServiceId: UUID? = null,
)

/** A question as the organizer edits it. */
data class ServiceQuestionInput(
    @field:NotBlank @field:Size(max = MAX_PROMPT) val prompt: String,
    @field:Valid @field:Size(min = MIN_CHOICES, max = MAX_CHOICES) val choices: List<ServiceQuestionChoiceInput>,
)

/** The whole set of a service's questions, replaced at once. */
data class ServiceQuestionsUpdate(
    @field:Valid @field:Size(max = MAX_QUESTIONS) val questions: List<ServiceQuestionInput>,
)

data class ServiceQuestionChoiceView(
    val id: UUID,
    val label: String,
    val routeServiceId: UUID?,
)

data class ServiceQuestionView(
    val id: UUID,
    val prompt: String,
    val choices: List<ServiceQuestionChoiceView>,
)

/** A choice as the client sees it: a routed one carries the short code of the service it leads to. */
data class PublicQuestionChoice(
    val id: UUID,
    val label: String,
    val routeCode: String? = null,
)

data class PublicQuestion(
    val id: UUID,
    val prompt: String,
    val choices: List<PublicQuestionChoice>,
)

/** One answer a client gives when booking. */
data class BookingAnswerInput(
    val questionId: UUID,
    val choiceId: UUID,
)

/** A recorded answer, as staff read it in the console and the export. */
data class BookingAnswerView(
    val question: String,
    val answer: String,
)

/** WhatsApp's limits (JIKU-191): a button or list row title holds 20 characters, a list ten rows. */
const val MAX_QUESTIONS = 3
const val MIN_CHOICES = 2
const val MAX_CHOICES = 10
const val MAX_LABEL = 20
const val MAX_PROMPT = 140

/**
 * A service's guided questions (JIKU-191): the organizer edits them as a whole,
 * the client answers them before the times, and the answers are copied onto the
 * booking so staff see them in the console and the export.
 */
@Service
class ServiceQuestionService(
    private val questions: ServiceQuestionRepository,
    private val choices: ServiceQuestionChoiceRepository,
    private val answers: ServiceBookingAnswerRepository,
    private val services: ServiceRepository,
    private val links: ServiceLinkCodeService,
) {
    @Transactional(readOnly = true)
    fun list(serviceId: UUID): List<ServiceQuestionView> {
        requireService(serviceId)
        return load(serviceId).map { (question, options) ->
            ServiceQuestionView(
                requireNotNull(question.id),
                question.prompt,
                options.map { ServiceQuestionChoiceView(requireNotNull(it.id), it.label, it.routeServiceId) },
            )
        }
    }

    /** Replaces every question of [serviceId] with [update], once it fits WhatsApp's limits. */
    @Transactional
    fun replace(
        serviceId: UUID,
        update: ServiceQuestionsUpdate,
    ): List<ServiceQuestionView> {
        requireService(serviceId)
        validate(serviceId, update)
        val existing = questions.findByServiceIdOrderByPosition(serviceId)
        if (existing.isNotEmpty()) {
            choices.deleteByQuestionIdIn(existing.mapNotNull { it.id })
            questions.deleteAll(existing)
            questions.flush()
        }
        update.questions.forEachIndexed { index, input ->
            val question = questions.save(ServiceQuestion(serviceId, index, input.prompt.trim()))
            input.choices.forEachIndexed { position, choice ->
                choices.save(ServiceQuestionChoice(requireNotNull(question.id), position, choice.label.trim(), choice.routeServiceId))
            }
        }
        return list(serviceId)
    }

    /** The questions as the client sees them on the booking page. */
    @Transactional
    fun forClient(
        serviceId: UUID,
        tenantId: String,
    ): List<PublicQuestion> =
        load(serviceId).map { (question, options) ->
            PublicQuestion(
                requireNotNull(question.id),
                question.prompt,
                options.map { choice ->
                    PublicQuestionChoice(
                        requireNotNull(choice.id),
                        choice.label,
                        choice.routeServiceId?.let { links.forService(it, tenantId).code },
                    )
                },
            )
        }

    /**
     * Checks a client's answers before the booking is taken: one per question,
     * from that question's choices, and none that leads to another service.
     */
    @Transactional(readOnly = true)
    fun checkAnswers(
        serviceId: UUID,
        given: List<BookingAnswerInput>,
    ): List<BookingAnswerView> {
        val asked = load(serviceId)
        val byQuestion = given.associateBy { it.questionId }
        if (byQuestion.size != given.size || byQuestion.size != asked.size) throw unanswered()
        return asked.map { (question, options) ->
            val choice = options.firstOrNull { it.id == byQuestion[question.id]?.choiceId } ?: throw unanswered()
            if (choice.routeServiceId != null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This answer leads to another service")
            }
            BookingAnswerView(question.prompt, choice.label)
        }
    }

    /** Copies the checked answers onto the booking that was just taken. */
    @Transactional
    fun record(
        serviceId: UUID,
        bookingTokenHash: String,
        startsAt: Instant,
        checked: List<BookingAnswerView>,
    ) {
        checked.forEachIndexed { position, answer ->
            answers.save(ServiceBookingAnswer(serviceId, bookingTokenHash, startsAt, position, answer.question, answer.answer))
        }
    }

    /** Forgets a booking's answers along with the booking (JIKU-191). */
    @Transactional
    fun forget(bookingTokenHash: String) = answers.deleteByBookingTokenHash(bookingTokenHash)

    /** Forgets the answers of bookings that no longer exist, after expired requests are swept. */
    @Transactional
    fun forgetOrphans(): Int = answers.deleteOrphans()

    /** The answers of each booking, by its token hash. */
    @Transactional(readOnly = true)
    fun answersOf(hashes: Collection<String>): Map<String, List<BookingAnswerView>> =
        if (hashes.isEmpty()) {
            emptyMap()
        } else {
            answers
                .findByBookingTokenHashInOrderByPosition(hashes)
                .groupBy({ it.bookingTokenHash }, { BookingAnswerView(it.question, it.answer) })
        }

    private fun load(serviceId: UUID): List<Pair<ServiceQuestion, List<ServiceQuestionChoice>>> {
        val asked = questions.findByServiceIdOrderByPosition(serviceId)
        if (asked.isEmpty()) return emptyList()
        val options = choices.findByQuestionIdInOrderByPosition(asked.mapNotNull { it.id }).groupBy { it.questionId }
        return asked.map { it to options[it.id].orEmpty() }
    }

    private fun validate(
        serviceId: UUID,
        update: ServiceQuestionsUpdate,
    ) {
        if (update.questions.size > MAX_QUESTIONS) throw invalid("At most $MAX_QUESTIONS questions")
        update.questions.forEach { question ->
            if (question.choices.size !in
                MIN_CHOICES..MAX_CHOICES
            ) {
                throw invalid("Each question needs $MIN_CHOICES to $MAX_CHOICES answers")
            }
            val labels = question.choices.map { it.label.trim() }
            if (labels.any { it.isEmpty() || it.length > MAX_LABEL }) throw invalid("An answer holds 1 to $MAX_LABEL characters")
            if (labels.map { it.lowercase() }.toSet().size != labels.size) throw invalid("Two answers of a question are the same")
            question.choices.mapNotNull { it.routeServiceId }.forEach { target ->
                if (target == serviceId || !services.existsById(target)) throw invalid("An answer leads to an unknown service")
            }
        }
    }

    private fun requireService(serviceId: UUID) {
        TenantContext.require()
        if (!services.existsById(serviceId)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found")
    }

    private fun invalid(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

    private fun unanswered() = ResponseStatusException(HttpStatus.BAD_REQUEST, "Every question needs one of its answers")
}
