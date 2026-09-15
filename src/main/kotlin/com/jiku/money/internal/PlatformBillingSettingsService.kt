package com.jiku.money.internal

import com.jiku.money.BillingTierOption
import com.jiku.money.PayeeDetails
import com.jiku.money.PlatformBillingSettingsUpdate
import com.jiku.money.PlatformBillingSettingsView
import com.jiku.money.SubscriptionPlanOption
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant

/**
 * Réglages de facturation pilotés par le bureau admin (bénéficiaire des
 * virements, grilles de prix des paliers et des formules d'abonnement),
 * persistés en base pour être modifiables sans redéploiement. Tant qu'une
 * colonne n'a pas été renseignée, la configuration d'environnement reste le
 * défaut — la base est une surcouche, jamais un second catalogue à maintenir.
 */
@Service
class PlatformBillingSettingsService(
    private val settings: PlatformBillingSettingsRepository,
    private val billingProperties: BillingProperties,
    private val subscriptionProperties: SubscriptionProperties,
    private val manualProperties: ManualPaymentProperties,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(readOnly = true)
    fun tiers(): List<BillingProperties.Tier> {
        val row = settings.findById(PlatformBillingSettings.ROW_ID).orElse(null)
        val json = row?.tierPricesJson?.takeIf { it.isNotBlank() } ?: return billingProperties.tiers
        return runCatching {
            objectMapper.readValue<List<BillingProperties.Tier>>(json)
        }.getOrNull() ?: billingProperties.tiers
    }

    @Transactional(readOnly = true)
    fun plans(): List<Plan> {
        val row = settings.findById(PlatformBillingSettings.ROW_ID).orElse(null)
        val json = row?.subscriptionPlansJson?.takeIf { it.isNotBlank() } ?: return subscriptionProperties.plans
        return runCatching {
            objectMapper.readValue<List<Plan>>(json)
        }.getOrNull() ?: subscriptionProperties.plans
    }

    @Transactional(readOnly = true)
    fun tierByName(name: String): BillingProperties.Tier? = tiers().firstOrNull { it.name.equals(name, ignoreCase = true) }

    @Transactional(readOnly = true)
    fun planByName(name: String): Plan? = plans().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Prix du prépaiement (minor unit) : formules de la base, périodes de la config. */
    @Transactional(readOnly = true)
    fun priceMinor(
        plan: Plan,
        months: Int,
    ): Long? =
        subscriptionProperties.period(months)?.let { p ->
            plan.priceMinorPerMonth * months * p.factorMilli / 1_000
        }

    /** La formule couvrant [activeResources] ressources actives. */
    @Transactional(readOnly = true)
    fun planForResources(activeResources: Long): Plan =
        plans().sortedBy { it.maxResources }.firstOrNull { activeResources <= it.maxResources }
            ?: plans().maxByOrNull { it.maxResources }
            ?: subscriptionProperties.plans.maxByOrNull { it.maxResources }!!

    /** Le palier correspondant à [invitedGuests] invités (pour l'affichage). */
    @Transactional(readOnly = true)
    fun tierForUsage(invitedGuests: Long): String =
        when {
            invitedGuests <= billingProperties.freeTierGuests -> BillingProperties.FREE_TIER
            else -> tiers().firstOrNull { invitedGuests <= it.maxGuests }?.name ?: BillingProperties.CUSTOM_TIER
        }

    /** Bénéficiaire effectif : la base d'abord, puis la configuration d'environnement. */
    @Transactional(readOnly = true)
    fun payeeDetails(): PayeeDetails {
        val row = settings.findById(PlatformBillingSettings.ROW_ID).orElse(null)
        return PayeeDetails(
            payeeName = row?.payeeName?.takeIf { it.isNotBlank() } ?: manualProperties.payeeName.takeIf { it.isNotBlank() },
            contactEmail = row?.payeeContactEmail?.takeIf { it.isNotBlank() },
            contactPhone = row?.payeeContactPhone?.takeIf { it.isNotBlank() },
            mobileMoneyNumber =
                row?.mobileMoneyNumber?.takeIf { it.isNotBlank() }
                    ?: manualProperties.mobileMoneyNumber.takeIf { it.isNotBlank() },
            mobileMoneyOperator =
                row?.mobileMoneyOperator?.takeIf { it.isNotBlank() }
                    ?: manualProperties.mobileMoneyOperator.takeIf { it.isNotBlank() },
            bankDetails =
                row?.bankDetails?.takeIf { it.isNotBlank() }
                    ?: manualProperties.bankDetails.takeIf { it.isNotBlank() },
        )
    }

    @Transactional(readOnly = true)
    fun view(): PlatformBillingSettingsView {
        val row = settings.findById(PlatformBillingSettings.ROW_ID).orElse(null)
        val managed = row?.tierPricesJson != null || row?.subscriptionPlansJson != null
        return PlatformBillingSettingsView(
            currency = billingProperties.currency,
            payee = payeeDetails(),
            tiers = tiers().map { BillingTierOption(it.name, it.maxGuests, it.priceMinor) },
            subscriptionPlans = plans().map { SubscriptionPlanOption(it.name, it.maxResources, it.priceMinorPerMonth) },
            managedInDatabase = managed,
        )
    }

    /** Remplace les réglages complets ; audit via [updatedBy]. */
    @Transactional
    fun update(
        update: PlatformBillingSettingsUpdate,
        updatedBy: String?,
    ): PlatformBillingSettingsView {
        val row = settings.findById(PlatformBillingSettings.ROW_ID).orElseGet { PlatformBillingSettings() }
        row.payeeName = update.payee.payeeName?.takeIf { it.isNotBlank() }
        row.payeeContactEmail = update.payee.contactEmail?.takeIf { it.isNotBlank() }
        row.payeeContactPhone = update.payee.contactPhone?.takeIf { it.isNotBlank() }
        row.mobileMoneyNumber = update.payee.mobileMoneyNumber?.takeIf { it.isNotBlank() }
        row.mobileMoneyOperator = update.payee.mobileMoneyOperator?.takeIf { it.isNotBlank() }
        row.bankDetails = update.payee.bankDetails?.takeIf { it.isNotBlank() }
        row.tierPricesJson = objectMapper.writeValueAsString(update.tiers)
        row.subscriptionPlansJson = objectMapper.writeValueAsString(update.subscriptionPlans)
        row.updatedAt = Instant.now()
        row.updatedBy = updatedBy
        settings.save(row)
        return view()
    }
}
