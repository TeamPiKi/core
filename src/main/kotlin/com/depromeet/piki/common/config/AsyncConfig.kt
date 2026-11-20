package com.depromeet.piki.common.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.support.ContextPropagatingTaskDecorator
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer {
    private val log = LoggerFactory.getLogger(javaClass)

    // 실행기를 지정하지 않은 @Async 가 여기서 돎. Modulith 의 ApplicationModuleListener 가 그 경우
    override fun getAsyncExecutor(): Executor = eventPublicationExecutor()

    @Bean(EVENT_PUBLICATION_EXECUTOR)
    fun eventPublicationExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 2
            queueCapacity = 100
            setThreadNamePrefix("event-publication-")
            setTaskDecorator(ContextPropagatingTaskDecorator())
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            initialize()
        }

    @Bean(ITEM_PARSING_EXECUTOR)
    fun itemParsingExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 8
            maxPoolSize = 8
            // 인메모리 대기열 X
            queueCapacity = 0
            setThreadNamePrefix("item-parsing-")
            setTaskDecorator(ContextPropagatingTaskDecorator())
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            initialize()
        }

    @Bean(NOTIFICATION_EXECUTOR)
    fun notificationExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 4
            queueCapacity = 200
            setThreadNamePrefix("notification-")
            setTaskDecorator(ContextPropagatingTaskDecorator())
            // TODO 예외 발생 없음
            setRejectedExecutionHandler { _, executor ->
                log.warn(
                    "알림 executor 포화로 태스크 거부 — 알림 1건 유실 (activeCount={}, queueSize={})",
                    executor.activeCount,
                    executor.queue.size,
                )
            }
            initialize()
        }

    companion object {
        const val ITEM_PARSING_EXECUTOR = "itemParsingExecutor"
        const val EVENT_PUBLICATION_EXECUTOR = "eventPublicationExecutor"
        const val NOTIFICATION_EXECUTOR = "notificationExecutor"
    }
}
