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
 * Un professionnel qui s'est déclaré intéressé par la prise de rendez-vous
 * (JIKU-98), avant que le produit n'existe.
 *
 * Volontairement **non** tenant-scopé : au moment
 * où il laisse ses coordonnées, le prospect n'a pas de compte. C'est de la donnée de
 * niveau plateforme, visible uniquement depuis le bureau d'administration.
 *
 * Le téléphone porte une contrainte d'unicité : un professionnel qui soumet le
 * formulaire deux fois met à jour sa piste plutôt que d'en créer une seconde.
 */
@Entity
@Table(name = "prospect_lead")
class ProspectLead(
    @Column(name = "business_name", nullable = false)
    var businessName: String,
    @Column(name = "contact_name", nullable = false)
    var contactName: String,
    @Column(name = "phone", nullable = false, unique = true)
    var phone: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "sector", nullable = false)
    var sector: ProspectSector,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "email")
    var email: String? = null

    @Column(name = "city")
    var city: String? = null

    /** Volume hebdomadaire déclaré : sert à trier qui rappeler en premier. */
    @Column(name = "weekly_volume")
    var weeklyVolume: String? = null

    @Column(name = "note")
    var note: String? = null

    /** Provenance issue du `?src=` des liens de campagne. */
    @Column(name = "source")
    var source: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ProspectStatus = ProspectStatus.NEW

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "contacted_at")
    var contactedAt: Instant? = null
}

/**
 * Secteurs proposés au formulaire. La liste vient des prescripteurs existants du
 * plan GTM — ce sont eux qui ont déjà une relation avec la plateforme, donc le coût
 * d'acquisition le plus bas.
 */
enum class ProspectSector {
    COUTURE,
    COIFFURE_BEAUTE,
    PHOTOGRAPHIE,
    TRAITEUR,
    SALLE_RECEPTION,
    SANTE,
    RESTAURATION,
    AUTRE,
}

enum class ProspectStatus {
    NEW,
    CONTACTED,
    QUALIFIED,
    REJECTED,
}
