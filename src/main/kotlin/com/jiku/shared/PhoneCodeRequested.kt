package com.jiku.shared

/**
 * An organization asked to confirm [phone] for its personal verification. The
 * messaging module delivers [code] by SMS; the code is never stored in clear.
 */
data class PhoneCodeRequested(
    val tenantId: String,
    val phone: String,
    val code: String,
)
