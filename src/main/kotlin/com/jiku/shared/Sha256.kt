package com.jiku.shared

import java.security.MessageDigest

/** The SHA-256 digest of [value] (UTF-8) as lowercase hex: how tokens, codes and visitor marks are stored. */
fun sha256Hex(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
