package com.jiku.messaging.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** Gabarits client du tenant courant (JIKU-91), filtrés par le tenant. */
interface TenantTemplateRepository : JpaRepository<TenantTemplate, UUID> {
    fun findByNameAndChannel(
        name: String,
        channel: String,
    ): TenantTemplate?

    fun findByNameOrderByChannelAsc(name: String): List<TenantTemplate>
}

/** Termes produit du tenant courant (JIKU-91), filtrés par le tenant. */
interface TenantVocabularyRepository : JpaRepository<TenantVocabulary, UUID> {
    fun findByKey(key: String): TenantVocabulary?

    fun findAllByOrderByKeyAsc(): List<TenantVocabulary>
}
