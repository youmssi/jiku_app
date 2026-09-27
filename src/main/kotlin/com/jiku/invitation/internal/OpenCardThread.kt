package com.jiku.invitation.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * The open invitation a number last wrote about on the cards number (JIKU-185),
 * so a number typed alone is read as its companions. Routing only, outside the
 * tenant filter: a message reaches the webhook before any tenant is known.
 */
@Entity
@Table(name = "open_card_thread")
class OpenCardThread(
    @Id
    @Column(name = "phone")
    val phone: String,
    @Column(name = "code", nullable = false)
    var code: String,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

interface OpenCardThreadRepository : JpaRepository<OpenCardThread, String>
