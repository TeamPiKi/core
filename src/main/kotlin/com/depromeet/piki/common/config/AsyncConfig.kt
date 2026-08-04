package com.depromeet.piki.common.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.support.ContextPropagatingTaskDecorator
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

// 등록 시 item 파싱(원격 extractor 호출)을 HTTP 응답과 분리해 백그라운드로 돌리기 위한 executor.
// 제출자는 ItemParsingScheduler 하나뿐이고(dispatch·recover), 디스패처가 **가용 슬롯만큼만 claim** 하므로
// 대기실을 두지 않는다 — queueCapacity=0 은 SynchronousQueue 라 제출이 곧장 스레드로 넘어가거나 거부된다.
//
// 왜 큐를 없앴나: 작업의 대기열은 이미 DB 의 PENDING 행이다(item_snapshots 가 곧 작업 큐). 인메모리 큐를 두면
// 같은 대기열이 둘이 되어 (a) 휘발성 사본에 작업이 쌓여 크래시 시 PROCESSING 좀비로 남고(recover 가 되살릴 비용),
// (b) 실행도 시작 안 한 작업이 PROCESSING("담는 중")으로 사용자에게 위장되며, (c) 큐가 차서 거부되면 그 행이
// attempt 를 태워 과부하가 사용자 아이템 FAILED 로 번진다. 대기는 durable 한 PENDING 에서 하는 것이 맞다.
//
// 부수 효과로 core→max 성장이 비로소 정상 동작한다: ThreadPoolExecutor 는 **큐가 꽉 차야** 스레드를 늘리므로,
// 큐가 100 이던 이전 설정에서는 maxPoolSize 8 이 사실상 도달 불가였다(실질 "4 스레드 + 100칸 대기실").
// 단일 인스턴스 MVP 기준의 보수적 풀 크기다. 운영 트래픽이 보이면 application.yml 로 빼 튜닝한다.
@Configuration
@EnableAsync
class AsyncConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    // 반환 타입이 ThreadPoolTaskExecutor 인 이유: 디스패처가 이 풀의 가용 슬롯(maxPoolSize - activeCount)을 읽어
    // claim 수를 정한다. 용량이 이 빈의 계약 일부가 됐으므로 구체 타입으로 노출한다.
    @Bean(ITEM_PARSING_EXECUTOR)
    fun itemParsingExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 8
            queueCapacity = 0
            setThreadNamePrefix("item-parsing-")
            // 부모(톰캣) 스레드의 trace context·MDC·observation 을 워커 스레드로 전파한다. trace context 는
            // ThreadLocal 기반이라 이게 없으면 @Async 경계에서 끊겨, 워커 로그에 traceId 가 빈 채로 찍혀
            // 한 요청의 전체 로그를 Loki 에서 traceId 로 추적할 수 없다. context-propagation(micrometer) 의
            // ContextSnapshot 으로 등록된 모든 ThreadLocalAccessor(brave trace context·MDC 등)를 전파한다.
            setTaskDecorator(ContextPropagatingTaskDecorator())
            // 기본 AbortPolicy(거부 시 throw)를 쓴다. 호출 스레드에서 동기 실행하는 CallerRunsPolicy 는 폴링 스레드를
            // 외부 호출로 붙잡아 dispatch·recover 주기를 통째로 밀리게 하므로 쓰지 않는다.
            // 정상 흐름에서는 디스패처가 가용 슬롯만큼만 claim 하므로 거부가 발화하지 않는다 — 슬롯 계산과 제출 사이의
            // 미세한 레이스(activeCount 는 근사치)를 위한 안전망이다. 거부되면 그 행은 PROCESSING 으로 남고
            // recover 가 재실행한다(execution at-least-once, #461). URL·이미지 모두 작업 큐 경유라 처리가 같다.
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
