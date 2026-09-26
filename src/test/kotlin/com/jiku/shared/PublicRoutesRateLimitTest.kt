package com.jiku.shared

import com.jiku.TestcontainersConfiguration
import com.jiku.shared.ratelimit.RateLimitProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.util.AntPathMatcher
import kotlin.test.assertTrue

/**
 * Regression (JIKU-113): every public route that writes without an account is
 * throttled. The appointment, queue and counter links used to have no budget,
 * so a script could hoard every slot.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PublicRoutesRateLimitTest {
    @Autowired
    lateinit var properties: RateLimitProperties

    private val matcher = AntPathMatcher()

    @Test
    fun `public write routes all fall under a rate-limit policy`() {
        val publicWrites =
            listOf(
                "/appointments/some-token/book",
                "/appointments/some-token/line",
                "/r/ABC123/book",
                "/r/ABC123/line",
                "/line/some-token/next",
                "/line-codes/ABC123",
                "/rsvp/some-token/confirm",
                "/checkin/some-token/scan",
                "/prospects",
            )
        for (path in publicWrites) {
            assertTrue(
                properties.policies.values.any { policy -> policy.paths.any { matcher.match(it, path) } },
                "$path has no rate-limit policy",
            )
        }
    }
}
