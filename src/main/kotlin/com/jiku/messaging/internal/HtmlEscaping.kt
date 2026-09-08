package com.jiku.messaging.internal

/** Échappement HTML unique du module : le rendu insère des chaînes tenant/données. */
internal fun escapeHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
