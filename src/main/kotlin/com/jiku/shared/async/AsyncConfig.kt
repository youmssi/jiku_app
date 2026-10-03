package com.jiku.shared.async

import com.jiku.shared.TenantContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/** Names of the background executors (JIKU-215), for `@Async`. */
object Executors {
    /** Volume work: invitation batches, tickets, cancellations, reminders. */
    const val BULK = "bulkExecutor"

    /** Work someone is waiting for: "your turn", a phone code, an answer in the chat. */
    const val URGENT = "urgentExecutor"
}

/**
 * Background work runs on two executors (JIKU-215) so a batch of 300
 * invitations never delays the "your turn" message of a client at the desk.
 *
 * - [Executors.BULK]: a fixed set of threads and an unbounded queue. Nothing is
 *   refused under load; work waits its turn, and a batch never blocks the
 *   thread that queued it.
 * - [Executors.URGENT]: one virtual thread per task, capped. Tasks are short
 *   and mostly wait on the network, which virtual threads make cheap.
 *
 * Both propagate the caller's [TenantContext] to the task and clear it after.
 */
@Configuration
@EnableAsync
class AsyncConfig(
    @Value("\${jiku.async.bulk-threads:8}") private val bulkThreads: Int,
    @Value("\${jiku.async.urgent-concurrency:64}") private val urgentConcurrency: Int,
) {
    @Bean(Executors.BULK)
    fun bulkExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = bulkThreads
            maxPoolSize = bulkThreads
            queueCapacity = Int.MAX_VALUE
            setThreadNamePrefix("jiku-bulk-")
            setTaskDecorator(tenantContextTaskDecorator())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(SHUTDOWN_GRACE_SECONDS)
            initialize()
        }

    @Bean(Executors.URGENT)
    fun urgentExecutor(): SimpleAsyncTaskExecutor =
        SimpleAsyncTaskExecutor("jiku-urgent-").apply {
            setVirtualThreads(true)
            concurrencyLimit = urgentConcurrency
            setTaskDecorator(tenantContextTaskDecorator())
        }

    private fun tenantContextTaskDecorator(): TaskDecorator =
        TaskDecorator { runnable ->
            val tenantId = TenantContext.get()
            Runnable {
                tenantId?.let { TenantContext.set(it) }
                try {
                    runnable.run()
                } finally {
                    TenantContext.clear()
                }
            }
        }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS = 30
    }
}
