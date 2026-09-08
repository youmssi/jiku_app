package com.jiku.shared.observability

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Un DSN placeholder (par ex. « DSN Sentry » laissé par erreur dans un
 * environnement) ne doit jamais activer le traqueur Sentry ni empêcher le
 * démarrage : seules les URLs http(s) bien formées le font.
 */
class ErrorDsnGuardTest {
    @Test
    fun `a real sentry dsn enables the tracker`() {
        assertTrue(isUsableErrorDsn("https://public@o1.ingest.sentry.io/4500000000"))
    }

    @Test
    fun `blank and placeholder values never enable the tracker`() {
        assertFalse(isUsableErrorDsn(""))
        assertFalse(isUsableErrorDsn("DSN Sentry"))
        assertFalse(isUsableErrorDsn("DSN SENTRY"))
    }

    @Test
    fun `malformed or non-http urls never enable the tracker`() {
        assertFalse(isUsableErrorDsn("not-a-url"))
        assertFalse(isUsableErrorDsn("ftp://ingest.sentry.io/x"))
        assertFalse(isUsableErrorDsn("http s://x"))
    }
}
