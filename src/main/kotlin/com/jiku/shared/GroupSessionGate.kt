package com.jiku.shared

/**
 * How many clients one resource may serve in the same slot, by services plan
 * (référentiel métier §3: Solo 1, Teams 10, Organisation 30). Exposed in
 * `shared` so the catalog module can cap a group session without depending on
 * the billing module, which provides the implementation.
 */
fun interface GroupSessionGate {
    /** The most clients per resource and per slot the current tenant's plan allows. */
    fun maxClientsPerSlot(): Int
}
