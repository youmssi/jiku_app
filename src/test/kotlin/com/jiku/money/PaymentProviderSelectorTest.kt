package com.jiku.money

import com.jiku.money.internal.PaymentCallback
import com.jiku.money.internal.PaymentInitiation
import com.jiku.money.internal.PaymentInitiationRequest
import com.jiku.money.internal.PaymentInstruction
import com.jiku.money.internal.PaymentProperties
import com.jiku.money.internal.PaymentProvider
import com.jiku.money.internal.PaymentProviderSelector
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JIKU-105: the billing flow never names a provider. Configuration picks the
 * adapter that starts payments, every registered adapter stays reachable by
 * name for its own callbacks, and a misconfiguration stops startup instead of
 * falling back to another provider.
 */
class PaymentProviderSelectorTest {
    private val sandbox = FakeProvider("sandbox")
    private val aggregator = FakeProvider("aggregator")

    @Test
    fun `configuration picks the adapter that starts new payments`() {
        val selector = PaymentProviderSelector(listOf(sandbox, aggregator), PaymentProperties(provider = "aggregator"))

        assertSame(aggregator, selector.active)
    }

    @Test
    fun `every registered adapter stays reachable by name`() {
        val selector = PaymentProviderSelector(listOf(sandbox, aggregator), PaymentProperties(provider = "aggregator"))

        assertSame(sandbox, selector.byName("sandbox"))
        assertSame(aggregator, selector.byName("aggregator"))
        assertNull(selector.byName("unknown"))
    }

    @Test
    fun `a provider name that matches no adapter stops startup`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                PaymentProviderSelector(listOf(sandbox), PaymentProperties(provider = "aggregator"))
            }

        assertTrue(failure.message!!.contains("aggregator"))
        assertTrue(failure.message!!.contains("[sandbox]"))
    }

    @Test
    fun `a currency uses its own adapter, or the default one when none is set`() {
        val selector =
            PaymentProviderSelector(
                listOf(sandbox, aggregator),
                PaymentProperties(provider = "sandbox", byCurrency = mapOf("GNF" to "aggregator", "XOF" to " ")),
            )

        assertSame(aggregator, selector.forCurrency("GNF"))
        assertSame(aggregator, selector.forCurrency("gnf"))
        assertSame(sandbox, selector.forCurrency("XOF"))
        assertSame(sandbox, selector.forCurrency("XAF"))
    }

    @Test
    fun `a currency set to none offers no online payment`() {
        val selector =
            PaymentProviderSelector(
                listOf(sandbox, aggregator),
                PaymentProperties(
                    provider = "aggregator",
                    byCurrency =
                        mapOf(
                            "USD" to "none",
                        ),
                ),
            )

        assertNull(selector.forCurrency("USD"))
        assertSame(aggregator, selector.forCurrency("GNF"))
    }

    @Test
    fun `a currency naming no adapter stops startup`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                PaymentProviderSelector(listOf(sandbox), PaymentProperties(byCurrency = mapOf("GNF" to "dexpay")))
            }

        assertTrue(failure.message!!.contains("by-currency.GNF 'dexpay'"), failure.message)
    }

    @Test
    fun `two adapters sharing a name stop startup`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                PaymentProviderSelector(listOf(sandbox, FakeProvider("sandbox")), PaymentProperties())
            }

        assertEquals("Payment provider names must be unique; duplicated: [sandbox]", failure.message)
    }

    private class FakeProvider(
        override val name: String,
    ) : PaymentProvider {
        override fun initiate(request: PaymentInitiationRequest) =
            PaymentInitiation(
                providerReference = "$name-${request.paymentId}",
                instruction = PaymentInstruction("REDIRECT", "https://pay.test"),
            )

        override fun parseCallback(
            rawBody: String,
            signature: String?,
        ): PaymentCallback? = null
    }
}
