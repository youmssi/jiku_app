package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Event
import com.jiku.catalog.internal.EventRepository
import com.jiku.catalog.internal.TicketType
import com.jiku.catalog.internal.TicketTypeRepository
import com.jiku.shared.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.Executors

/**
 * JIKU-93 : les deux plafonds — celui de l'événement et celui de la catégorie —
 * doivent être respectés **ensemble**.
 *
 * C'est la promesse la plus visible du produit : ne jamais admettre plus de monde
 * que la salle n'en contient, ni plus de VIP que le carré n'en tient. Un compteur
 * qui ment se découvre à la porte, devant les invités.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TicketTypeCapacityTest {
    @Autowired
    lateinit var events: EventRepository

    @Autowired
    lateinit var ticketTypes: TicketTypeRepository

    @Autowired
    lateinit var api: EventModuleApi

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun tenant() = "tenant-type-${UUID.randomUUID()}"

    private fun evenement(
        tenant: String,
        capaciteGlobale: Int?,
    ): UUID {
        TenantContext.set(tenant)
        val event = Event(name = "Gala", timezone = "Africa/Conakry").apply { maxCapacity = capaciteGlobale }
        return requireNotNull(events.saveAndFlush(event).id)
    }

    private fun categorie(
        eventId: UUID,
        label: String,
        capacite: Int?,
    ): UUID {
        val type =
            TicketType(eventId = eventId, label = label, colorHex = "#1E293B").apply { maxCapacity = capacite }
        return requireNotNull(ticketTypes.saveAndFlush(type).id)
    }

    @Test
    fun `une categorie pleine refuse, meme si l'evenement a de la place`() {
        val t = tenant()
        val eventId = evenement(t, capaciteGlobale = 100)
        val vip = categorie(eventId, "VIP", capacite = 1)

        assertThat(api.reserveAttendanceSlot(eventId, vip)).isTrue()
        assertThat(api.reserveAttendanceSlot(eventId, vip)).isFalse()
    }

    @Test
    fun `un refus de categorie ne consomme aucune place globale`() {
        val t = tenant()
        val eventId = evenement(t, capaciteGlobale = 100)
        val vip = categorie(eventId, "VIP", capacite = 1)

        api.reserveAttendanceSlot(eventId, vip)
        val apresSucces = events.findById(eventId).orElseThrow().confirmedCount

        // Le second refus ne doit rien laisser derrière lui : une place globale
        // consommée sans place de catégorie ferait refuser à la porte quelqu'un
        // que le système croit admis.
        assertThat(api.reserveAttendanceSlot(eventId, vip)).isFalse()
        assertThat(events.findById(eventId).orElseThrow().confirmedCount).isEqualTo(apresSucces)
    }

    @Test
    fun `l'evenement plein refuse, meme si la categorie a de la place`() {
        val t = tenant()
        val eventId = evenement(t, capaciteGlobale = 1)
        val vip = categorie(eventId, "VIP", capacite = 50)

        assertThat(api.reserveAttendanceSlot(eventId, vip)).isTrue()
        assertThat(api.reserveAttendanceSlot(eventId, vip)).isFalse()
        // La catégorie n'a pas été entamée par la tentative refusée.
        assertThat(ticketTypes.findById(vip).orElseThrow().confirmedCount).isEqualTo(1)
    }

    @Test
    fun `des reservations concurrentes ne depassent jamais le plafond de categorie`() {
        val t = tenant()
        val eventId = evenement(t, capaciteGlobale = 500)
        val vip = categorie(eventId, "VIP", capacite = 5)

        val executor = Executors.newFixedThreadPool(8)
        try {
            val succes =
                (1..20)
                    .map {
                        executor.submit<Boolean> {
                            TenantContext.set(t)
                            try {
                                api.reserveAttendanceSlot(eventId, vip)
                            } finally {
                                TenantContext.clear()
                            }
                        }
                    }.map { it.get() }
                    .count { it }

            assertThat(succes).isEqualTo(5)
            TenantContext.set(t)
            assertThat(ticketTypes.findById(vip).orElseThrow().confirmedCount).isEqualTo(5)
            // Et surtout : le compteur global reflète exactement les succès.
            assertThat(events.findById(eventId).orElseThrow().confirmedCount).isEqualTo(5)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `une categorie sans plafond ne limite que par l'evenement`() {
        val t = tenant()
        val eventId = evenement(t, capaciteGlobale = 2)
        val salle = categorie(eventId, "Salle", capacite = null)

        assertThat(api.reserveAttendanceSlot(eventId, salle)).isTrue()
        assertThat(api.reserveAttendanceSlot(eventId, salle)).isTrue()
        assertThat(api.reserveAttendanceSlot(eventId, salle)).isFalse()
    }

    @Test
    fun `sans categorie le comportement est strictement celui d'avant`() {
        val t = tenant()
        val eventId = evenement(t, capaciteGlobale = 2)

        assertThat(api.reserveAttendanceSlot(eventId, null)).isTrue()
        assertThat(api.reserveAttendanceSlot(eventId, null)).isTrue()
        assertThat(api.reserveAttendanceSlot(eventId, null)).isFalse()
    }

    @Test
    fun `liberer rend la place globale et celle de la categorie`() {
        val t = tenant()
        val eventId = evenement(t, capaciteGlobale = 10)
        val vip = categorie(eventId, "VIP", capacite = 1)

        api.reserveAttendanceSlot(eventId, vip)
        api.releaseAttendanceSlot(eventId, vip)

        assertThat(events.findById(eventId).orElseThrow().confirmedCount).isEqualTo(0)
        assertThat(ticketTypes.findById(vip).orElseThrow().confirmedCount).isEqualTo(0)
        // La place rendue est réutilisable.
        assertThat(api.reserveAttendanceSlot(eventId, vip)).isTrue()
    }

    @Test
    fun `reclasser un confirme deplace sa place sans toucher au compteur global`() {
        val t = tenant()
        val eventId = evenement(t, capaciteGlobale = 10)
        val vip = categorie(eventId, "VIP", capacite = 5)
        val standard = categorie(eventId, "Standard", capacite = 5)

        api.reserveAttendanceSlot(eventId, vip)
        assertThat(api.moveTicketTypeSlot(vip, standard)).isTrue()

        assertThat(ticketTypes.findById(vip).orElseThrow().confirmedCount).isEqualTo(0)
        assertThat(ticketTypes.findById(standard).orElseThrow().confirmedCount).isEqualTo(1)
        // La personne est toujours dans la salle : le compteur global n'a pas bougé.
        assertThat(events.findById(eventId).orElseThrow().confirmedCount).isEqualTo(1)
    }

    @Test
    fun `un deplacement vers une categorie pleine ne libere pas l'ancienne`() {
        val t = tenant()
        val eventId = evenement(t, capaciteGlobale = 10)
        val vip = categorie(eventId, "VIP", capacite = 5)
        val carre = categorie(eventId, "Carré", capacite = 1)

        api.reserveAttendanceSlot(eventId, vip)
        api.reserveAttendanceSlot(eventId, carre)

        assertThat(api.moveTicketTypeSlot(vip, carre)).isFalse()
        // L'invité reste où il était, avec sa place.
        assertThat(ticketTypes.findById(vip).orElseThrow().confirmedCount).isEqualTo(1)
        assertThat(ticketTypes.findById(carre).orElseThrow().confirmedCount).isEqualTo(1)
    }

    @Test
    fun `un tenant ne reserve pas dans la categorie d'un autre`() {
        val proprietaire = tenant()
        val eventId = evenement(proprietaire, capaciteGlobale = 10)
        val vip = categorie(eventId, "VIP", capacite = 5)

        TenantContext.set(tenant())
        assertThat(api.reserveAttendanceSlot(eventId, vip)).isFalse()

        TenantContext.set(proprietaire)
        assertThat(ticketTypes.findById(vip).orElseThrow().confirmedCount).isEqualTo(0)
    }
}
