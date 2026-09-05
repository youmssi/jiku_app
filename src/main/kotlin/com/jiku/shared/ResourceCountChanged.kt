package com.jiku.shared

/**
 * Le nombre de ressources actives d'un tenant vient de changer (JIKU-90), publié
 * par le module catalog après création/activation/désactivation d'une ressource.
 * Le module money écoute : l'ouverture d'une première ressource active matérialise
 * un abonnement, et la photo resources_active est rafraîchie.
 */
data class ResourceCountChanged(
    val tenantId: String,
    val activeResources: Long,
)
