package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Event
import com.jiku.catalog.internal.EventRepository
import com.jiku.catalog.internal.QuorumMode
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
 * JIKU-94 : l'horodatage d'atteinte du quorum est la valeur probante. Il doit
 * être écrit **une seule fois**, résister à la concurrence, et survivre aux
 * départs qui font retomber le compte sous le seuil.
 *
 * C'est ce qui distingue une preuve opposable d'un simple compteur.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class QuorumReachedAtTest {
    @Autowired
    lateinit var events: EventRepository

    @Autowired
    lateinit var api: EventModuleApi

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun eventAvecQuorum(tenant: String): UUID {
        TenantContext.set(tenant)
        val event =
            Event(name = "Assemblée générale", timezone = "Africa/Conakry").apply {
                ensureQuorum().apply {
                    mode = QuorumMode.ABSOLUTE
                    absolute = 3
                }
            }
        return requireNotNull(events.saveAndFlush(event).id)
    }

    @Test
    fun `l'horodatage n'est ecrit qu'une fois et n'est jamais reecrit`() {
        val tenant = "tenant-quorum-${UUID.randomUUID()}"
        val eventId = eventAvecQuorum(tenant)

        api.markQuorumReached(eventId)
        val premiere =
            events
                .findById(eventId)
                .orElseThrow()
                .quorum
                ?.reachedAt
        assertThat(premiere).isNotNull()

        // Les arrivées suivantes ne doivent jamais déplacer la date : c'est elle
        // qu'on opposera en cas de contestation un mois plus tard.
        Thread.sleep(20)
        api.markQuorumReached(eventId)
        assertThat(
            events
                .findById(eventId)
                .orElseThrow()
                .quorum
                ?.reachedAt,
        ).isEqualTo(premiere)
    }

    @Test
    fun `deux portiers qui franchissent le seuil ensemble n'ecrivent qu'une date`() {
        val tenant = "tenant-quorum-${UUID.randomUUID()}"
        val eventId = eventAvecQuorum(tenant)

        val executor = Executors.newFixedThreadPool(6)
        try {
            (1..6)
                .map {
                    executor.submit {
                        TenantContext.set(tenant)
                        try {
                            api.markQuorumReached(eventId)
                        } finally {
                            TenantContext.clear()
                        }
                    }
                }.forEach { it.get() }
        } finally {
            executor.shutdown()
        }

        TenantContext.set(tenant)
        assertThat(
            events
                .findById(eventId)
                .orElseThrow()
                .quorum
                ?.reachedAt,
        ).isNotNull()
    }

    @Test
    fun `un depart fait retomber l'etat mais conserve la date d'atteinte`() {
        val tenant = "tenant-quorum-${UUID.randomUUID()}"
        val eventId = eventAvecQuorum(tenant)

        // Quatre présents sur trois requis : atteint.
        val atteint = requireNotNull(api.quorum(eventId, totalGuests = 10, checkedIn = 4))
        assertThat(atteint.reached).isTrue()
        api.markQuorumReached(eventId)

        // Deux départs : le compte retombe sous le seuil.
        val retombe = requireNotNull(api.quorum(eventId, totalGuests = 10, checkedIn = 2))
        assertThat(retombe.reached).isFalse()
        // Mais la date de première atteinte demeure — les deux sont vraies, et
        // l'écran doit montrer les deux.
        assertThat(retombe.reachedAt).isNotNull()
    }

    @Test
    fun `un evenement sans quorum ne renvoie rien`() {
        TenantContext.set("tenant-sans-quorum-${UUID.randomUUID()}")
        val event = Event(name = "Mariage", timezone = "Africa/Conakry")
        val id = requireNotNull(events.saveAndFlush(event).id)

        assertThat(api.quorum(id, totalGuests = 100, checkedIn = 50)).isNull()
    }

    @Test
    fun `le quorum compte les presents et non les confirmations`() {
        val tenant = "tenant-quorum-${UUID.randomUUID()}"
        val eventId = eventAvecQuorum(tenant)

        // Une assemblée délibère avec ceux qui sont dans la salle : 2 présents
        // sur 3 requis n'atteint pas le quorum, quel que soit le nombre d'inscrits.
        assertThat(requireNotNull(api.quorum(eventId, totalGuests = 500, checkedIn = 2)).reached).isFalse()
        assertThat(requireNotNull(api.quorum(eventId, totalGuests = 3, checkedIn = 3)).reached).isTrue()
    }

    @Test
    fun `un tenant ne peut pas horodater le quorum d'un autre`() {
        val proprietaire = "tenant-a-${UUID.randomUUID()}"
        val eventId = eventAvecQuorum(proprietaire)

        TenantContext.set("tenant-b-${UUID.randomUUID()}")
        api.markQuorumReached(eventId)

        TenantContext.set(proprietaire)
        assertThat(
            events
                .findById(eventId)
                .orElseThrow()
                .quorum
                ?.reachedAt,
        ).isNull()
    }

    @Test
    fun `la date posee ne bouge pas quand la regle change ensuite`() {
        val tenant = "tenant-quorum-${UUID.randomUUID()}"
        val eventId = eventAvecQuorum(tenant)

        api.markQuorumReached(eventId)
        val posee =
            events
                .findById(eventId)
                .orElseThrow()
                .quorum
                ?.reachedAt

        // L'organisateur corrige sa règle en cours d'assemblée.
        val event = events.findById(eventId).orElseThrow()
        event.ensureQuorum().absolute = 50
        events.saveAndFlush(event)

        // La première atteinte sous l'ancienne règle demeure : on ne réécrit pas
        // l'histoire d'une délibération.
        assertThat(
            events
                .findById(eventId)
                .orElseThrow()
                .quorum
                ?.reachedAt,
        ).isEqualTo(posee)
    }
}
