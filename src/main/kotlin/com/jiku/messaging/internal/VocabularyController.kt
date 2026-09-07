package com.jiku.messaging.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Termes produit du tenant (JIKU-91) : ticket, rendez-vous, sans-rendez-vous,
 * personne, professionnel, action. Une valeur vide ramène au terme par défaut.
 */
@RestController
@RequestMapping("/settings/vocabulary")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class VocabularyController(
    private val personalization: TenantPersonalizationService,
) {
    @GetMapping
    fun list(): List<VocabularyEntry> = personalization.vocabulary()

    @PutMapping
    fun save(
        @RequestBody updates: List<VocabularyUpdate>,
    ): List<VocabularyEntry> = personalization.saveVocabulary(updates)
}
