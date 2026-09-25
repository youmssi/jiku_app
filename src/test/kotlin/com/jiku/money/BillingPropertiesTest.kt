package com.jiku.money

import com.jiku.money.internal.BillingProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * JIKU-53: the Guinea market grid (FREE up to 100, BRONZE/ARGENT/OR fixed
 * tiers, CUSTOM beyond) resolves correctly at every band boundary, and each
 * tier carries its ADR 105 price in GNF, FCFA and USD.
 */
class BillingPropertiesTest {
    private val properties = BillingProperties()

    @Test
    fun `tier boundaries resolve to the correct name`() {
        assertEquals("FREE", properties.tierForUsage(1))
        assertEquals("FREE", properties.tierForUsage(100))
        assertEquals("BRONZE", properties.tierForUsage(101))
        assertEquals("BRONZE", properties.tierForUsage(300))
        assertEquals("ARGENT", properties.tierForUsage(301))
        assertEquals("ARGENT", properties.tierForUsage(600))
        assertEquals("OR", properties.tierForUsage(601))
        assertEquals("OR", properties.tierForUsage(1_000))
        assertEquals("CUSTOM", properties.tierForUsage(1_001))
        assertEquals("CUSTOM", properties.tierForUsage(50_000))
    }

    @Test
    fun `tierForAllowance returns the fixed tier or null beyond it`() {
        assertEquals("BRONZE", properties.tierForAllowance(150)?.name)
        assertEquals("OR", properties.tierForAllowance(1_000)?.name)
        assertNull(properties.tierForAllowance(1_001))
    }

    @Test
    fun `fixed tiers carry a round price in each billing currency`() {
        assertEquals(PriceList(225_000, 15_000, 2_500), properties.tierForAllowance(300)?.price)
        assertEquals(PriceList(375_000, 25_000, 4_500), properties.tierForAllowance(600)?.price)
        assertEquals(PriceList(600_000, 40_000, 7_000), properties.tierForAllowance(1_000)?.price)
        assertEquals(35, properties.beyondPerGuest.amountMinor("XOF"))
    }
}
