package com.jiku.messaging.internal

/** Une entrée du vocabulaire d'un tenant, telle que renvoyée à l'éditeur. */
data class VocabularyEntry(
    val key: String,
    val label: String,
    val defaultValue: String,
    /** Terme effectif : surcharge du tenant, sinon défaut. */
    val value: String,
    val overridden: Boolean,
)

/** Mise à jour d'un terme : value null ou vide = revenir au terme par défaut. */
data class VocabularyUpdate(
    val key: String,
    val value: String?,
)

/** Vue d'un gabarit surchargeable pour l'éditeur. */
data class TemplateSummary(
    val name: String,
    val label: String,
    val channels: List<String>,
)

/** Vue d'un canal d'un gabarit : corps du défaut, surcharge, état. */
data class TemplateChannelView(
    val channel: String,
    val defaultBody: String,
    val body: String,
    val isOverride: Boolean,
    val active: Boolean,
)

/** Vue complète d'un gabarit (un canal est absent quand non disponible). */
data class TemplateDetail(
    val name: String,
    val label: String,
    val channels: List<TemplateChannelView>,
    val variables: List<TemplateVariable>,
)

data class TemplateVariable(
    val name: String,
    val label: String,
    val sample: String,
    val required: Boolean,
)

/** Écriture d'un gabarit pour un canal. */
data class TemplateUpdate(
    val channel: String,
    val body: String,
    val active: Boolean = true,
)

/** Demande d'aperçu : canal, corps optionnel (sinon le corps actuel/défaut). */
data class TemplatePreviewRequest(
    val channel: String,
    val body: String? = null,
)

data class TemplatePreviewResponse(
    val body: String,
)
