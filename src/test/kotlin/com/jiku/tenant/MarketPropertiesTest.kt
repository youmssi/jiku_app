package com.jiku.tenant

import com.jiku.tenant.internal.Market
import com.jiku.tenant.internal.MarketProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * JIKU-107: an organization's currency is its country's, and only configured
 * countries can sign up.
 */
class MarketPropertiesTest {
    private val markets = MarketProperties(defaultCountry = "GN", supportedCountries = setOf("GN", "CI", "CM"))

    @Test
    fun `a country resolves to its legal currency`() {
        assertEquals(Market("GN", "GNF"), markets.marketOf("GN"))
        assertEquals(Market("CI", "XOF"), markets.marketOf("ci"))
        assertEquals(Market("CM", "XAF"), markets.marketOf(" cm "))
    }

    @Test
    fun `no country means the default market`() {
        assertEquals(Market("GN", "GNF"), markets.marketOf(null))
        assertEquals(Market("GN", "GNF"), markets.marketOf(""))
    }

    @Test
    fun `a country outside the supported markets has no market`() {
        assertNull(markets.marketOf("SN"))
    }

    @Test
    fun `a default country outside the supported ones stops startup`() {
        assertFailsWith<IllegalStateException> { MarketProperties(defaultCountry = "SN", supportedCountries = setOf("GN")) }
    }

    @Test
    fun `a code that names no country stops startup`() {
        assertFailsWith<IllegalArgumentException> { MarketProperties(defaultCountry = "GN", supportedCountries = setOf("GN", "ZZ")) }
    }
}
