package com.jiku.shared.observability

/**
 * The MDC (mapped diagnostic context) keys attached to every log line so a request
 * can be traced across the call stack. Structured (JSON) logging emits them as
 * fields; the local console pattern prints them inline. Kept in one place so
 * producers (the correlation filter, the tenant context) and consumers (the error
 * handler) never drift on the key names.
 */
object MdcKeys {
    /** Correlation id unique to one inbound HTTP request. */
    const val REQUEST_ID = "requestId"

    /** The tenant the request is acting as, once bound (absent for public requests). */
    const val TENANT_ID = "tenantId"
}
