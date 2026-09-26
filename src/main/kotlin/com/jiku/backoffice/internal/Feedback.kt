package com.jiku.backoffice.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * What an organizer tells the platform team (JIKU-133): a rating right after a
 * key action, or a message the team answers. Platform-level data, read across
 * organizations from the admin desk; [tenantId] is context, not a filter.
 */
@Entity
@Table(name = "feedback")
class Feedback(
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    val kind: FeedbackKind,
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: String,
    @Column(name = "tenant_id", updatable = false)
    val tenantId: String?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    /** The action a rating is about ("event_published"…); null for messages. */
    @Column(name = "moment")
    var moment: String? = null

    /** 1 to 5; null when a rating prompt was dismissed. */
    @Column(name = "score")
    var score: Int? = null

    @Column(name = "message")
    var message: String? = null

    /** The app page the person was on. */
    @Column(name = "page")
    var page: String? = null

    /** Where the team replies; for messages only. */
    @Column(name = "contact_email")
    var contactEmail: String? = null

    @Column(name = "language")
    var language: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: FeedbackStatus = FeedbackStatus.NEW

    @Column(name = "admin_note")
    var adminNote: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
}

enum class FeedbackKind {
    RATING,
    PROBLEM,
    QUESTION,
    IDEA,
    COMPLAINT,
    ;

    companion object {
        /** The kinds a person can write, as opposed to the rating prompt. */
        val MESSAGES = entries - RATING
    }
}

enum class FeedbackStatus {
    NEW,
    IN_PROGRESS,
    ANSWERED,
    CLOSED,
}
