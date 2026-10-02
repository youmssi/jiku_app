package com.jiku.shared

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.Instant

/**
 * Whether a person agreed to receive Jikū's news and tips, and the proof of it
 * (JIKU-201): when, which wording they saw, and where they gave or withdrew it.
 * Marketing only ever reaches people whose [granted] is true; guests of an
 * organization are never in scope.
 */
@Embeddable
class MarketingConsent(
    @Column(name = "marketing_consent", nullable = false)
    var granted: Boolean = false,
    @Column(name = "marketing_consent_at")
    var decidedAt: Instant? = null,
    @Column(name = "marketing_consent_version", length = 32)
    var textVersion: String? = null,
    @Column(name = "marketing_consent_source", length = 16)
    var source: String? = null,
) {
    /** Records a decision; a refusal that was never an agreement leaves no trace. */
    fun record(
        granted: Boolean,
        textVersion: String?,
        source: String,
        now: Instant = Instant.now(),
    ) {
        if (!granted && !this.granted && decidedAt == null) return
        this.granted = granted
        this.decidedAt = now
        this.textVersion = textVersion
        this.source = source
    }

    companion object {
        const val SOURCE_SIGNUP = "signup"
        const val SOURCE_PROSPECT = "prospect"
        const val SOURCE_SETTINGS = "settings"
    }
}
