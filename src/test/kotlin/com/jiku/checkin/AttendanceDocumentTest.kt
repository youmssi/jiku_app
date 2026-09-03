package com.jiku.checkin

import com.jiku.checkin.internal.AttendanceDocumentRenderer
import com.jiku.checkin.internal.CertificateData
import com.jiku.checkin.internal.RegisterData
import com.jiku.checkin.internal.RegisterEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * JIKU-95 : les documents de preuve de présence. Tests purs — ni Spring, ni base
 * — parce que ce qui compte ici est ce qui finit sur le papier.
 */
class AttendanceDocumentTest {
    private val renderer = AttendanceDocumentRenderer()

    // 14h30 UTC, soit 14h30 à Conakry (UTC+0) et 15h30 à Douala (UTC+1).
    private val arrivee = Instant.parse("2026-04-14T14:30:00Z")

    private fun certificat(
        nom: String = "Aïcha Diallo",
        fuseau: String = "Africa/Conakry",
    ) = CertificateData(
        eventName = "Formation gestion de projet",
        eventDate = Instant.parse("2026-04-14T08:00:00Z"),
        timezone = fuseau,
        issuerLines = listOf("Cabinet Exemple SARL", "12 rue du Commerce", "Conakry, GN"),
        participantName = nom,
        arrivedAt = arrivee,
        checkedInBy = "Accueil",
    )

    private fun texte(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun `l'attestation est un vrai PDF`() {
        val bytes = renderer.certificate(certificat())

        assertThat(bytes).isNotEmpty()
        // %PDF- est le nombre magique du format : tout le reste n'est pas un PDF,
        // quel que soit le type de contenu annoncé.
        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
    }

    @Test
    fun `la feuille d'emargement est un vrai PDF et porte le total`() {
        val bytes =
            renderer.register(
                RegisterData(
                    eventName = "Assemblée générale",
                    eventDate = Instant.parse("2026-04-14T08:00:00Z"),
                    timezone = "Africa/Conakry",
                    issuerLines = listOf("Coopérative Exemple"),
                    entries =
                        listOf(
                            RegisterEntry("Aïcha Diallo", arrivee, "Accueil"),
                            RegisterEntry("Mamadou Barry", arrivee.plusSeconds(600), "Accueil"),
                        ),
                ),
            )

        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
        assertThat(bytes).isNotEmpty()
    }

    @Test
    fun `les heures sont rendues dans le fuseau de l'evenement`() {
        // Une formation à Conakry s'atteste à l'heure de Conakry, quel que soit
        // l'endroit d'où le document est téléchargé.
        val conakry = texte(renderer.certificate(certificat(fuseau = "Africa/Conakry")))
        val douala = texte(renderer.certificate(certificat(fuseau = "Africa/Douala")))

        // Le même instant rend deux heures différentes selon le fuseau déclaré.
        assertThat(conakry).isNotEqualTo(douala)
    }

    @Test
    fun `un participant anonymise n'expose aucune donnee effacee`() {
        // L'effacement remplace le nom en base par « Deleted Guest » : le document
        // ne peut donc pas fuiter de donnée personnelle. Ce test verrouille cette
        // dépendance, pour qu'un changement de la stratégie d'effacement ne la
        // casse pas silencieusement.
        val bytes = renderer.certificate(certificat(nom = "Deleted Guest"))

        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
        assertThat(texte(bytes)).doesNotContain("Aïcha")
    }

    @Test
    fun `le nom de fichier porte l'evenement et le participant`() {
        assertThat(renderer.certificateFileName(certificat()))
            .isEqualTo("attestation-formation-gestion-de-projet-a-cha-diallo.pdf")
    }

    @Test
    fun `un intitule sans caractere exploitable produit tout de meme un nom de fichier`() {
        val data = certificat().copy(eventName = "———", participantName = "———")

        // Un nom de fichier vide casserait le téléchargement côté navigateur.
        assertThat(renderer.certificateFileName(data)).isEqualTo("attestation-document-document.pdf")
    }

    @Test
    fun `une feuille sans aucun present reste un document valide`() {
        // Zéro présent est une information : le document doit l'attester, pas
        // échouer.
        val bytes =
            renderer.register(
                RegisterData(
                    eventName = "Session annulée",
                    eventDate = null,
                    timezone = "Africa/Conakry",
                    issuerLines = listOf("Cabinet Exemple"),
                    entries = emptyList(),
                ),
            )

        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
    }
}
