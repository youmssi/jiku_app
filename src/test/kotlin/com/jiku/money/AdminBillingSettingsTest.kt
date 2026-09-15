package com.jiku.money

import com.jiku.TestcontainersConfiguration
import com.jiku.money.internal.PlatformBillingSettingsService
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Réglages de facturation du bureau admin : la configuration d'environnement
 * reste le défaut tant que rien n'est enregistré ; une mise à jour prend le
 * dessus pour le bénéficiaire et les grilles de prix, et l'écriture remplace
 * tout (pas de fusion silencieuse).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AdminBillingSettingsTest {
    @Autowired
    lateinit var settings: PlatformBillingSettingsService

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `the view always exposes tiers plans and payee`() {
        val view = settings.view()
        assertTrue(view.tiers.isNotEmpty())
        assertTrue(view.subscriptionPlans.isNotEmpty())
        assertEquals("GNF", view.currency)
    }

    @Test
    fun `a saved update becomes the source of truth for tiers and payee`() {
        val before = settings.tiers()
        try {
            val updated =
                settings.update(
                    PlatformBillingSettingsUpdate(
                        payee =
                            PayeeDetails(
                                payeeName = "Jikū SARL",
                                contactEmail = "facturation@jiku.app",
                                contactPhone = "+224620000000",
                                mobileMoneyNumber = "+224620000001",
                                mobileMoneyOperator = "Orange Money",
                                bankDetails = "Banque de test, compte 000",
                            ),
                        tiers =
                            listOf(
                                BillingTierOption("BRONZE", 400, 200_000),
                                BillingTierOption("OR", 1_200, 600_000),
                            ),
                        subscriptionPlans =
                            listOf(
                                SubscriptionPlanOption("Solo", 1, 120_000),
                                SubscriptionPlanOption("Salon", 15, 550_000),
                            ),
                    ),
                    updatedBy = "admin@jiku.app",
                )

            assertTrue(updated.managedInDatabase)
            val tier = settings.tierByName("bronze")
            assertNotNull(tier)
            assertEquals(400, tier.maxGuests)
            assertEquals(200_000, tier.priceMinor)
            assertEquals(null, settings.tierByName("ARGENT"), "removed tiers must disappear")

            val payee = settings.payeeDetails()
            assertEquals("Jikū SARL", payee.payeeName)
            assertEquals("+224620000000", payee.contactPhone)
            assertEquals("+224620000001", payee.mobileMoneyNumber)

            val plan = settings.planByName("solo")
            assertNotNull(plan)
            assertEquals(120_000, plan.priceMinorPerMonth)
        } finally {
            // Remet l'état par défaut pour les autres tests de la suite.
            settings.update(
                PlatformBillingSettingsUpdate(
                    payee =
                        PayeeDetails(
                            payeeName = null,
                            contactEmail = null,
                            contactPhone = null,
                            mobileMoneyNumber = null,
                            mobileMoneyOperator = null,
                            bankDetails = null,
                        ),
                    tiers = before.map { BillingTierOption(it.name, it.maxGuests, it.priceMinor) },
                    subscriptionPlans =
                        settings
                            .plans()
                            .map { SubscriptionPlanOption(it.name, it.maxResources, it.priceMinorPerMonth) },
                ),
                updatedBy = null,
            )
        }
    }
}
