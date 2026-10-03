package com.jiku.shared.scheduling

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * Each scheduled job takes a lock in PostgreSQL (`shedlock` table) before it
 * runs (JIKU-215), so with several API instances behind a load balancer
 * (ADR 107) a job still runs once. The database clock decides, so instances
 * with drifting clocks agree. A job that dies keeps its lock at most
 * [DEFAULT_LOCK_AT_MOST_FOR], then another instance may run it.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = SchedulingConfig.DEFAULT_LOCK_AT_MOST_FOR)
class SchedulingConfig {
    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration
                .builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )

    companion object {
        const val DEFAULT_LOCK_AT_MOST_FOR = "PT30M"
    }
}
