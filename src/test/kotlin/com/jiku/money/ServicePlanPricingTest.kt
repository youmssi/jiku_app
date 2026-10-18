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
    fun `Services Pro charges each person who serves clients`() {
        val pro = plans.getValue("Services Pro")
        assertEquals(60_000, pro.monthlyMinor("GNF", people = 1))
        assertEquals(300_000, pro.monthlyMinor("GNF", people = 5))
        assertEquals(20_000, pro.monthlyMinor("XOF", people = 5))
        assertEquals(3_500, pro.monthlyMinor("USD", people = 5))
        assertEquals(2, plans.size, "only Solo and Services Pro are on offer")
    }

    @Test
    fun `Solo is free for one person and holds no one else`() {
        val solo = plans.getValue("Solo")
        assertTrue(solo.free)
        assertTrue(solo.covers(1))
        assertFalse(solo.covers(2))
        assertTrue(plans.getValue("Services Pro").covers(50))
    }
}
