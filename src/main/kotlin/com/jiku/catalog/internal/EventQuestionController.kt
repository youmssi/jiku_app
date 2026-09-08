package com.jiku.catalog.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import org.springframework.stereotype.Service as SpringService
import org.springframework.transaction.annotation.Transactional

/**
 * Questions personnalisées d'un événement (JIKU-77) : posées à l'invité au moment
 * de répondre (menu, transport, table…). Tenant-scopé par le filtre de
 * persistance ; l'événement est borné par le chemin.
 */
@RestController
@RequestMapping("/events/{eventId}/questions")
@PreAuthorize("hasRole('ORGANIZER')")
class EventQuestionController(
    private val questions: EventQuestionService,
) {
    @GetMapping
    fun list(
        @PathVariable eventId: UUID,
    ): List<EventQuestionResponse> = questions.list(eventId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: CreateEventQuestionRequest,
    ): EventQuestionResponse = questions.create(eventId, request)

    @DeleteMapping("/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable eventId: UUID,
        @PathVariable questionId: UUID,
    ) = questions.delete(eventId, questionId)
}

data class CreateEventQuestionRequest(
    @field:NotBlank @field:Size(max = 500) val prompt: String,
    val required: Boolean = false,
    val position: Int = 0,
)

data class EventQuestionResponse(
    val id: UUID,
    val eventId: UUID,
    val prompt: String,
    val required: Boolean,
    val position: Int,
)

@SpringService
class EventQuestionService(
    private val questions: EventQuestionRepository,
    private val events: EventRepository,
) {
    @Transactional(readOnly = true)
    fun list(eventId: UUID): List<EventQuestionResponse> =
        questions.findByEventIdOrderByPositionAsc(eventId).map { it.toResponse() }

    @Transactional
    fun create(
        eventId: UUID,
        request: CreateEventQuestionRequest,
    ): EventQuestionResponse {
        requireEvent(eventId)
        val question = EventQuestion(eventId = eventId, prompt = request.prompt.trim()).apply {
            required = request.required
            position = request.position
        }
        return questions.save(question).toResponse()
    }

    @Transactional
    fun delete(
        eventId: UUID,
        questionId: UUID,
    ) {
        val question = load(eventId, questionId)
        questions.delete(question)
    }

    private fun requireEvent(eventId: UUID) {
        if (!events.existsById(eventId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        }
    }

    private fun load(
        eventId: UUID,
        questionId: UUID,
    ): EventQuestion =
        questions.findById(questionId).orElse(null)?.takeIf { it.eventId == eventId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found")

    private fun EventQuestion.toResponse() =
        EventQuestionResponse(
            id = requireNotNull(id),
            eventId = eventId,
            prompt = prompt,
            required = required,
            position = position,
        )
}
