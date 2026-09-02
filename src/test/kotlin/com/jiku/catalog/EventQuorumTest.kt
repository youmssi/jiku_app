package com.jiku.catalog

import com.jiku.catalog.internal.EventQuorum
import com.jiku.catalog.internal.QuorumMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * JIKU-94 : le calcul du seuil de quorum. Tests purs — aucun contexte Spring,
 * aucune base — parce que c'est de l'arithmétique, et que c'est l'arithmétique
 * qui décide si une délibération est valable.
 */
class EventQuorumTest {
    @Test
    fun `une fraction arrondit au superieur`() {
        val moitie = EventQuorum(mode = QuorumMode.FRACTION, numerator = 1, denominator = 2)

        // 181 membres, majorité de 1/2 → 91 présents exigés, pas 90.
        // Arrondir à l'inférieur validerait une assemblée qui n'atteint pas sa
        // propre règle statutaire.
        assertThat(moitie.requiredFor(181)).isEqualTo(91)
        assertThat(moitie.requiredFor(180)).isEqualTo(90)
        assertThat(moitie.requiredFor(1)).isEqualTo(1)
    }

    @Test
    fun `les deux tiers se calculent sur des effectifs indivisibles`() {
        val deuxTiers = EventQuorum(mode = QuorumMode.FRACTION, numerator = 2, denominator = 3)

        assertThat(deuxTiers.requiredFor(100)).isEqualTo(67)
        assertThat(deuxTiers.requiredFor(99)).isEqualTo(66)
        assertThat(deuxTiers.requiredFor(10)).isEqualTo(7)
    }

    @Test
    fun `un quorum absolu ignore l'effectif`() {
        val absolu = EventQuorum(mode = QuorumMode.ABSOLUTE, absolute = 25)

        assertThat(absolu.requiredFor(500)).isEqualTo(25)
        assertThat(absolu.requiredFor(10)).isEqualTo(25)
    }

    @Test
    fun `aucun quorum configure ne produit aucun seuil`() {
        assertThat(EventQuorum().isConfigured()).isFalse()
        assertThat(EventQuorum().requiredFor(100)).isNull()
        assertThat(EventQuorum(mode = QuorumMode.NONE).isConfigured()).isFalse()
    }

    @Test
    fun `une fraction incomplete ne produit aucun seuil plutot qu'un seuil faux`() {
        // Mieux vaut aucune carte quorum qu'une carte affichant un seuil inventé :
        // l'organisateur verrait un chiffre auquel il ferait confiance.
        assertThat(EventQuorum(mode = QuorumMode.FRACTION, numerator = 1).requiredFor(100)).isNull()
        assertThat(EventQuorum(mode = QuorumMode.FRACTION, denominator = 2).requiredFor(100)).isNull()
        assertThat(
            EventQuorum(mode = QuorumMode.FRACTION, numerator = 1, denominator = 0).requiredFor(100),
        ).isNull()
        assertThat(EventQuorum(mode = QuorumMode.ABSOLUTE).requiredFor(100)).isNull()
    }
}
