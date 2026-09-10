package com.jiku.money.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * La ligne unique (id=1) des réglages de facturation pilotés par le bureau
 * admin : le bénéficiaire des virements (Mobile Money ou banque) et les grilles
 * de prix. Les colonnes JSON restent nulles tant que l'opérateur n'a rien
 * personnalisé — les valeurs de configuration d'environnement demeurent alors le
 * défaut (voir [PlatformBillingSettingsService]).
 */
@Entity
@Table(name = "platform_billing_settings")
class PlatformBillingSettings(
    @Id
    @Column(name = "id", nullable = false)
    val id: Long = ROW_ID,
) {
    @Column(name = "payee_name")
    var payeeName: String? = null

    @Column(name = "payee_contact_email")
    var payeeContactEmail: String? = null

    @Column(name = "payee_contact_phone")
    var payeeContactPhone: String? = null

    @Column(name = "mobile_money_number")
    var mobileMoneyNumber: String? = null

    @Column(name = "mobile_money_operator")
    var mobileMoneyOperator: String? = null

    @Column(name = "bank_details")
    var bankDetails: String? = null

    @Column(name = "tier_prices_json")
    var tierPricesJson: String? = null

    @Column(name = "subscription_plans_json")
    var subscriptionPlansJson: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Column(name = "updated_by")
    var updatedBy: String? = null

    companion object {
        const val ROW_ID = 1L
    }
}

interface PlatformBillingSettingsRepository : JpaRepository<PlatformBillingSettings, Long>
