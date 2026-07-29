package com.jiku.booking

import com.jiku.booking.internal.RefundPolicy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * JIKU-55: the cancellation refund policy across its three windows — more than
 * 60 days out is fully refundable, 30 to 60 days is half, under 30 days is
 * nothing (the date was blocked and cannot be resold on short notice).
 */
class RefundPolicyTest {
    @Test
    fun `more than 60 days out refunds in full`() {
        assertEquals(BigDecimal.ONE, RefundPolicy.refundRate(61))
        assertEquals(100_000, RefundPolicy.refundAmountMinor(100_000, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 3)))
    }

    @Test
    fun `exactly 60 and down to 30 days out refunds half`() {
        assertEquals(BigDecimal("0.50"), RefundPolicy.refundRate(60))
        assertEquals(BigDecimal("0.50"), RefundPolicy.refundRate(30))
        assertEquals(50_000, RefundPolicy.refundAmountMinor(100_000, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `under 30 days out refunds nothing`() {
        assertEquals(BigDecimal.ZERO, RefundPolicy.refundRate(29))
        assertEquals(BigDecimal.ZERO, RefundPolicy.refundRate(0))
        assertEquals(0, RefundPolicy.refundAmountMinor(100_000, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20)))
    }
}
