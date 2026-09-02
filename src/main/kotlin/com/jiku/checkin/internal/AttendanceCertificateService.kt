package com.jiku.checkin.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.invitation.InvitationModuleApi
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import com.jiku.ticket.TicketInfo
import com.jiku.ticket.TicketingModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Assemble les preuves de présence (JIKU-95) : attestation nominative et feuille
 * d'émargement.
 *
 * Le document est opposable, donc il porte l'identité **légale** de
 * l'organisation quand elle est renseignée — un bailleur ne se satisfait pas
 * d'un nom d'affichage. À défaut, le nom d'affichage sert de repli plutôt que de
 * refuser le document : une attestation imparfaite vaut mieux qu'aucune.
 */
@Service
class AttendanceCertificateService(
    private val events: EventModuleApi,
    private val invitation: InvitationModuleApi,
    private val ticketing: TicketingModuleApi,
    private val tenants: TenantModuleApi,
    private val renderer: AttendanceDocumentRenderer,
) {
    @Transactional(readOnly = true)
    fun certificate(
        eventId: UUID,
        guestId: UUID,
    ): RenderedDocument {
        val event =
            events.findEvent(eventId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Événement introuvable")
        val guest =
            invitation.findGuest(guestId)?.takeIf { it.eventId == eventId }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Participant introuvable")
        val ticket = ticketing.findByGuest(guestId)

        // Une attestation ne s'émet que pour quelqu'un qui est venu : sinon elle
        // atteste d'un fait qui n'a pas eu lieu, ce qui est précisément ce qu'un
        // bailleur cherche à écarter.
        val arrivedAt =
            ticket?.takeIf { it.status == TicketInfo.STATUS_CHECKED_IN }?.checkedInAt
                ?: throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ce participant n'a pas été enregistré à l'entrée",
                )

        val data =
            CertificateData(
                eventName = event.name,
                eventDate = event.startDateTime,
                timezone = event.timezone,
                issuerLines = issuerLines(),
                participantName = "${guest.firstName} ${guest.lastName}",
                arrivedAt = arrivedAt,
                checkedInBy = ticket.checkedInBy,
            )
        return RenderedDocument(renderer.certificate(data), renderer.certificateFileName(data))
    }

    @Transactional(readOnly = true)
    fun register(eventId: UUID): RenderedDocument {
        val event =
            events.findEvent(eventId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Événement introuvable")

        val guestsById = invitation.listGuests(eventId).associateBy { it.id }
        val entries =
            ticketing
                .findTicketsByEvent(eventId)
                .filter { it.status == TicketInfo.STATUS_CHECKED_IN && it.checkedInAt != null }
                .mapNotNull { ticket ->
                    val guest = guestsById[ticket.guestId] ?: return@mapNotNull null
                    RegisterEntry(
                        name = "${guest.firstName} ${guest.lastName}",
                        arrivedAt = requireNotNull(ticket.checkedInAt),
                        checkedInBy = ticket.checkedInBy,
                    )
                }.sortedBy { it.arrivedAt }

        val data =
            RegisterData(
                eventName = event.name,
                eventDate = event.startDateTime,
                timezone = event.timezone,
                issuerLines = issuerLines(),
                entries = entries,
            )
        return RenderedDocument(renderer.register(data), renderer.registerFileName(data))
    }

    /**
     * Bloc émetteur : identité légale si elle est complète, sinon le nom
     * d'affichage. Aucune donnée personnelle d'invité n'y figure jamais.
     */
    private fun issuerLines(): List<String> {
        val tenantId = TenantContext.get() ?: return emptyList()
        val tenant = tenants.findTenant(UUID.fromString(tenantId)) ?: return emptyList()
        val legal = tenant.legalIdentity
        return if (legal != null) {
            listOfNotNull(
                legal.legalName,
                legal.addressLine,
                "${legal.city}, ${legal.country}",
                legal.registrationNumber?.let { "RCCM $it" },
                legal.taxIdentifier?.let { "NIF $it" },
            )
        } else {
            listOf(tenant.displayName)
        }
    }
}

/** Un PDF rendu et le nom sous lequel le navigateur l'enregistre. */
data class RenderedDocument(
    val bytes: ByteArray,
    val fileName: String,
) {
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
