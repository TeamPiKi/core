package com.depromeet.piki.common.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.support.ContextPropagatingTaskDecorator
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

// 등록 시 item 파싱(원격 extractor 호출)을 HTTP 응답과 분리해 백그라운드로 돌리기 위한 executor.
// 단건 파싱은 60s 안에 끝나도록 원격 extractor 호출에 read timeout 을 잡아 둔다(#461, 원격 호출 read ≤ 약 55s < stale 60s).
// 단일 인스턴스 MVP 기준의 보수적 풀 크기다. 운영 트래픽이 보이면 application.yml 로 빼 튜닝한다.
@Configuration
@EnableAsync
class AsyncConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean(ITEM_PARSING_EXECUTOR)
    fun itemParsingExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 8
            queueCapacity = 100
            setThreadNamePrefix("item-parsing-")
            // 부모(톰캣) 스레드의 trace context·MDC·observation 을 워커 스레드로 전파한다. trace context 는
            // ThreadLocal 기반이라 이게 없으면 @Async 경계에서 끊겨, 워커 로그에 traceId 가 빈 채로 찍혀
            // 한 요청의 전체 로그를 Loki 에서 traceId 로 추적할 수 없다. context-propagation(micrometer) 의
            // ContextSnapshot 으로 등록된 모든 ThreadLocalAccessor(brave trace context·MDC 등)를 전파한다.
            setTaskDecorator(ContextPropagatingTaskDecorator())
            // 포화 시 기본 AbortPolicy 로 거부한다. 호출 스레드(톰캣)에서 동기 실행하는 CallerRunsPolicy 는
            // 외부 LLM 호출로 톰캣 워커 풀을 고갈시켜 무관한 API 까지 번지므로 쓰지 않는다.
            // 거부 처리는 경로마다 다르다: URL 파싱은 디스패처(ItemParsingScheduler)가 거부 시 PROCESSING 그대로 둬
            // recover 가 재실행하고(execution at-least-once, #461), 이미지 파싱은 등록 경로가 거부를 잡아 즉시 FAILED 로
            // 떨어뜨린다(이미지는 원본이 메모리 ByteArray 라 되살릴 수 없음).
            initialize()
        }

    // 알림 디스패치(AFTER_COMMIT 리스너)를 발행 트랜잭션·톰캣 워커와 분리하기 위한 executor.
    // 알림 작업은 DB 저장 + 채널 전달이라 item 파싱(외부 LLM 60s)보다 가벼워 풀을 작게 둔다.
    // 포화 시 로그만 남기고 버린다 — 알림은 best-effort 이고 원본은 이미 DB 에 영속화되므로 유실돼도 복원 가능.
    //
    // itemParsingExecutor 와 달리 기본 AbortPolicy(거부 시 throw)를 쓰지 않는다: 이 태스크는
    // @Async + @TransactionalEventListener(AFTER_COMMIT) 로 발행 트랜잭션이 커밋된 그 스레드 위에서
    // 동기 submit 된다. AbortPolicy 면 거부 예외(TaskRejectedException)가 AFTER_COMMIT 콜백을 타고
    // 발행부(ItemParsingService.markReady/markFailed) 호출 스택으로 역류해, 이미 정상 커밋된 item 에
    // "READY/FAILED 전이 실패" 오경보를 남긴다(item 상태 자체는 가드로 보존되지만 로그·메트릭이 오염된다).
    // 따라서 거부를 throw 대신 drop+warn 으로 처리해 "유실 허용" 의도를 실제로 구현한다.
    @Bean(NOTIFICATION_EXECUTOR)
    fun notificationExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 4
            queueCapacity = 200
            setThreadNamePrefix("notification-")
            // itemParsingExecutor 와 같은 이유로 trace context·MDC 를 워커 스레드로 전파해 알림 로그를 원 요청과 묶는다.
            setTaskDecorator(ContextPropagatingTaskDecorator())
            setRejectedExecutionHandler { _, executor ->
                log.warn(
                    "알림 executor 포화로 태스크 거부 — 알림 1건 유실 (activeCount={}, queueSize={})",
                    executor.activeCount,
                    executor.queue.size,
                )
            }
            initialize()
        }

    // 이미지 등록 v2 폴링 백스톱(PendingUploadPollingScheduler)을 공유 스케줄러 스레드에서 분리하기 위한 executor.
    // 폴링은 pending 마다 S3 HEAD(외부 호출)를 치므로 그대로 스케줄러 스레드에서 돌면 파싱 dispatch·SSE heartbeat 등
    // 다른 @Scheduled 를 굶긴다(ItemParsingScheduler 가 파싱을 @Async 로 빼는 것과 같은 결). 한 번에 한 폴링만 돌면 되므로
    // 풀·큐를 1·0 으로 두고, 겹친 제출은 스케줄러의 재진입 가드가 먼저 막지만 만일을 대비해 거부는 버린다(다음 주기가 재시도).
    @Bean(IMAGE_POLLING_EXECUTOR)
    fun imagePollingExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 1
            queueCapacity = 0
            setThreadNamePrefix("image-polling-")
            setTaskDecorator(ContextPropagatingTaskDecorator())
            setRejectedExecutionHandler { _, executor ->
                log.warn(
                    "이미지 폴링 executor 포화 — 이번 주기 건너뜀 (activeCount={}, queueSize={})",
                    executor.activeCount,
                    executor.queue.size,
                )
            }
            initialize()
        }

    companion object {
        const val ITEM_PARSING_EXECUTOR = "itemParsingExecutor"
        const val NOTIFICATION_EXECUTOR = "notificationExecutor"
        const val IMAGE_POLLING_EXECUTOR = "imagePollingExecutor"
    }
}
