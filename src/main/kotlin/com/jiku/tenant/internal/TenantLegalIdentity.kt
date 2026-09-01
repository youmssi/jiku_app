package com.jiku.tenant.internal

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * The organization's legal identity, as it must appear on an invoice a company or
 * public-sector buyer can actually process (JIKU-69).
 *
 * All fields are optional because an organization only supplies them when it needs
 * a compliant invoice. [isComplete] is what the invoice service checks: a document
 * is refused rather than issued with blanks where the buyer should be.
 */
@Embeddable
class TenantLegalIdentity(
    @Column(name = "legal_name")
    var legalName: String? = null,
    @Column(name = "registration_number")
    var registrationNumber: String? = null,
    @Column(name = "tax_identifier")
    var taxIdentifier: String? = null,
    @Column(name = "legal_address_line")
    var addressLine: String? = null,
    @Column(name = "legal_city")
    var city: String? = null,
    /** ISO 3166-1 alpha-2; selects the tax treatment applied to the invoice. */
    @Column(name = "legal_country")
    var country: String? = null,
) {
    /**
     * Whether enough is known to issue an invoice. The registration number and tax
     * identifier are deliberately not required: which of them a jurisdiction
     * demands differs, and requiring both would block buyers who legitimately have
     * only one.
     */
    fun isComplete(): Boolean =
        !legalName.isNullOrBlank() &&
            !addressLine.isNullOrBlank() &&
            !city.isNullOrBlank() &&
            !country.isNullOrBlank()
}
