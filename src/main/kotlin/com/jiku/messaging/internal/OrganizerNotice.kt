package com.jiku.messaging.internal

import com.jiku.shared.BaseTenantEntity
import com.jiku.shared.LiveScoped
import com.jiku.shared.LiveTopics
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * One line of an organization's notification bell (JIKU-314): what happened,
 * as a kind and its parameters, and the screen it is about. Every write tells
 * the bells of the organization to reload.
 */
@Entity
@Table(name = "organizer_notice")
class OrganizerNotice(
    @Column(name = "kind", nullable = false, updatable = false, length = 64)
    val kind: String,
    /** The parameters of the text, as a JSON object of strings. */
    @Column(name = "params", nullable = false, updatable = false)
    val params: String,
    /** The organizer-space path the notice opens; null when there is none. */
    @Column(name = "link", updatable = false)
    val link: String?,
) : BaseTenantEntity(),
    LiveScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "read_at")
    var readAt: Instant? = null

    override fun liveTopics(): Collection<String> = listOfNotNull(tenantId?.let(LiveTopics::notices))
}

interface OrganizerNoticeRepository : JpaRepository<OrganizerNotice, UUID> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<OrganizerNotice>

    fun countByReadAtIsNull(): Long

    fun findAllByReadAtIsNull(): List<OrganizerNotice>

    /** Platform-wide retention sweep; native on purpose, it spans every organization. */
    @Modifying
    @Query(value = "DELETE FROM organizer_notice WHERE created_at < :cutoff", nativeQuery = true)
    fun deleteOlderThan(
        @Param("cutoff") cutoff: Instant,
    ): Int
}
