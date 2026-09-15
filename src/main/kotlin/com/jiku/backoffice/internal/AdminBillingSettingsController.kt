package com.jiku.backoffice.internal

import com.jiku.money.BillingModuleApi
import com.jiku.money.PlatformBillingSettingsUpdate
import com.jiku.money.PlatformBillingSettingsView
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Le bureau admin pilote les réglages de facturation sans redéploiement : le
 * bénéficiaire des virements (nom, contact, Mobile Money, banque) et les grilles
 * de prix (paliers d'événement et formules d'abonnement). Une soumission
 * remplace les listes entières ; chaque changement est journalisé dans l'audit.
 */
@RestController
@RequestMapping("/admin/billing/settings")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminBillingSettingsController(
    private val billing: BillingModuleApi,
    private val auditService: AdminAuditService,
) {
    @GetMapping
    fun settings(): PlatformBillingSettingsView = billing.adminBillingSettings()

    @PutMapping
    fun update(
        authentication: Authentication,
        @Valid @RequestBody update: PlatformBillingSettingsUpdate,
    ): PlatformBillingSettingsView {
        val view = billing.adminUpdateBillingSettings(update, authentication.name)
        auditService.record(
            action = "BILLING_SETTINGS_UPDATED",
            target = "billing-settings",
            note = "payee=${view.payee.payeeName ?: "-"}, tiers=${view.tiers.size}, plans=${view.subscriptionPlans.size}",
        )
        return view
    }
}
