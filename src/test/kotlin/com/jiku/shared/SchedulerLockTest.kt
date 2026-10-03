package com.jiku.shared

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.ReputationMonitorJob
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

/** JIKU-215: a scheduled job takes its lock in PostgreSQL, so several API instances run it once. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SchedulerLockTest {
    @Autowired
    lateinit var job: ReputationMonitorJob

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `running a scheduled job records its lock`() {
        job.evaluate()

        val locks = jdbc.queryForObject("SELECT COUNT(*) FROM shedlock WHERE name = 'ReputationMonitorJob.evaluate'", Long::class.java)
        assertEquals(1L, locks)
    }
}
