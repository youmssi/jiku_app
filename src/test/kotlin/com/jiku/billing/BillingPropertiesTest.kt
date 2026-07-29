package com.jiku.billing

import com.jiku.billing.internal.BillingProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * JIKU-53: the Guinea market grid (FREE up to 100, BRONZE/ARGENT/OR fixed
 * tiers, CUSTOM beyond) resolves correctly at every band boundary, and CUSTOM
 * pricing is computed rather than looked up.
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
    fun `fixed tiers are priced in whole GNF, no minor unit`() {
        assertEquals(150_000, properties.tierForAllowance(300)?.priceMinor)
        assertEquals(300_000, properties.tierForAllowance(600)?.priceMinor)
        assertEquals(500_000, properties.tierForAllowance(1_000)?.priceMinor)
    }

    @Test
    fun `custom pricing combines per-guest sending cost and a flat setup fee`() {
        val custom = properties.custom
        // 1,500 guests at 5 USD-cents + a 1,500 USD-cent setup fee, at 8,760 GNF per USD.
        val expectedUsdCents = 5 * 1_500 + 1_500
        val expected = expectedUsdCents.toLong() * 8_760 / 100
        assertEquals(expected, custom.priceGnf(1_500))
        assertEquals(custom.priceGnf(2_000) > custom.priceGnf(1_000), true)
    }
}
