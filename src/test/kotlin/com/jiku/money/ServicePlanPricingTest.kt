package com.jiku.money

import com.jiku.money.internal.SubscriptionProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServicePlanPricingTest {
    private val plans = SubscriptionProperties().plans.associateBy { it.name }

    @Test
    fun `an organization is billed in its own franc, or in dollars elsewhere`() {
        assertEquals("GNF", PriceList.billingCurrencyFor("GNF"))
        assertEquals("XOF", PriceList.billingCurrencyFor("xof"))
        assertEquals("XAF", PriceList.billingCurrencyFor("XAF"))
        assertEquals("USD", PriceList.billingCurrencyFor("LRD"))
        assertEquals("USD", PriceList.billingCurrencyFor("CDF"))
    }

    @Test
    fun `a team plan charges its base price then each extra person`() {
        val teams = plans.getValue("Teams")
        assertEquals(150_000, teams.monthlyMinor("GNF", people = 1))
        assertEquals(150_000, teams.monthlyMinor("GNF", people = 2))
        assertEquals(300_000, teams.monthlyMinor("GNF", people = 5))
        assertEquals(20_500, teams.monthlyMinor("XOF", people = 5))
        assertEquals(3_500, teams.monthlyMinor("USD", people = 5))

        val organisation = plans.getValue("Organisation")
        assertEquals(550_000, organisation.monthlyMinor("GNF", people = 10))
    }

    @Test
    fun `Solo is free for one person and holds no one else`() {
        val solo = plans.getValue("Solo")
        assertTrue(solo.free)
        assertTrue(solo.covers(1))
        assertFalse(solo.covers(2))
        assertTrue(plans.getValue("Teams").covers(50))
    }
}
