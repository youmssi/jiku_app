package com.jiku.support

import com.jiku.mfa.internal.Totp
import java.time.Instant

/**
 * An authentication app for tests (JIKU-317). A code is accepted once and one
 * step either side of now, so each call returns the code of the next step not
 * used yet: three fresh codes fit in any 30 seconds.
 */
class Authenticator(
    private val secret: String,
) {
    private var lastStep: Long? = null

    fun next(): String {
        val now = Totp.step(Instant.now().epochSecond)
        val step = maxOf(now - 1, (lastStep ?: (now - 2)) + 1)
        check(step <= now + 1) { "No fresh code before the next 30-second step" }
        lastStep = step
        return Totp.code(secret, step)
    }

    /** A code the app would not show now; it uses up no step. */
    fun wrong(): String =
        Totp.code(secret, Totp.step(Instant.now().epochSecond)).let { code ->
            ((code.toInt() + 500_000) % 1_000_000).toString().padStart(6, '0')
        }
}
