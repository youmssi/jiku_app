package com.jiku.invitation.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Proof of an organizer's statement that the guests it imported agreed to
 * hear from it (JIKU-213): who said so, when, for which event and how many
 * guests. Meta requires that agreement before a business writes first.
 */
@Entity
@Table(name = "guest_consent_attestation")
class GuestConsentAttestation(
    @Column(name = "event_id", nullable = false)
    val eventId: UUID,
    @Column(name = "attested_by")
    val attestedBy: String?,
    @Column(name = "source", nullable = false)
    val source: String,
    @Column(name = "guest_count", nullable = false)
    val guestCount: Int,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "attested_at", nullable = false)
    val attestedAt: Instant = Instant.now()

    companion object {
        const val SOURCE_IMPORT = "IMPORT"
        const val SOURCE_EXISTING = "EXISTING"
    }
}

interface GuestConsentAttestationRepository : JpaRepository<GuestConsentAttestation, UUID>

/** The organizer's statement for an event's guests already imported without it (JIKU-213). */
data class ConsentAttestationResult(
    val attestedGuests: Int,
)
