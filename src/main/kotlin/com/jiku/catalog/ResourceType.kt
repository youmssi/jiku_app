package com.jiku.catalog

/**
 * Ce qu'une ressource représente (JIKU-84) : une personne, un lieu ou un
 * équipement, mobilisables pour un créneau. Les valeurs correspondent aux types
 * de la documentation produit (PERSONNE / LIEU / ÉQUIPEMENT).
 */
enum class ResourceType {
    PERSON,
    LOCATION,
    EQUIPMENT,
}
