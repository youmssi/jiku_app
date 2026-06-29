package com.jiku.shared.async

import com.jiku.shared.TenantContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Async execution for background work (e.g. sending invitations off the request
 * thread). The task decorator propagates the request's [TenantContext] to the
 * worker thread and clears it afterwards, so tenant-filtered queries run with the
 * correct tenant even though they execute outside the original request.
 */
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean("invitationExecutor")
    fun invitationExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 8
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("jiku-async-")
        executor.setTaskDecorator(tenantContextTaskDecorator())
        executor.initialize()
        return executor
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
}
