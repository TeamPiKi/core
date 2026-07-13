package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductLinkExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.ProductSnapshotException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

// itemParsingExecutor 스레드에서 "단건 파싱 한 번"을 수행한다. 외부 호출(extract)은 트랜잭션 바깥에서 끝내고,
// 상태 전이 영속화만 ItemParsingService(@Transactional) 에 위임해 짧은 트랜잭션으로 묶는다.
// 결과 처리는 셋으로 갈린다(execution at-least-once, #461): 성공 → READY, 확정 실패(상품 아님·이름 없음) → 즉시 FAILED,
// 일시 외부 오류 → 아무것도 안 하고 PROCESSING 유지(recover 가 상한까지 재실행). 재시도·종결 정책은 워커가 아니라 recover 가 쥔다.
// 전이 호출(markReady/markFailed)은 runCatching 으로 감싸 워커 스레드로 예외가 새지 않게 한다
// (recover 와의 레이스로 이미 전이됐거나, 추출값이 도메인 불변식을 위반하는 경우).
@Component
class AsyncItemParsingWorker(
    private val productLinkExtractor: ProductLinkExtractor,
    private val itemParsingService: ItemParsingService,
    private val transitionRetry: TransitionRetry,
    private val meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
) : ItemParsingWorker {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.ITEM_PARSING_EXECUTOR)
    override fun parse(
        itemId: Long,
        snapshotId: Long,
        link: ProductLink,
    ) {
        // 파싱 한 건을 "item.parse" span 하나로 묶는다 — 원격 extractor 호출이 그 자식 span 으로 붙고, extractor 내부의
        // fetch·structured·LLM span 은 traceparent 전파로 그 아래 이어져, 단건 파이프라인을 크로스서비스로 끝까지 펼쳐 볼 수 있다.
        // 디스패처가 @Scheduled 라 들어오는 trace 가 없어, 여기서 만들지 않으면 원격 호출 span 이 따로 떠 묶이지 않는다.
        Observation.createNotStarted(PARSE_OBSERVATION, observationRegistry).observe {
            val started = System.nanoTime()
            runCatching { productLinkExtractor.extract(link) }
                .onSuccess { snapshot -> onExtracted(itemId, snapshotId, link, snapshot, started) }
                .onFailure { e -> onExtractFailed(itemId, snapshotId, link, e) }
        }
    }

    private fun onExtracted(
        itemId: Long,
        snapshotId: Long,
        link: ProductLink,
        snapshot: ProductSnapshot,
        started: Long,
    ) {
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // 전이가 실패(추출값 도메인 검증 위반·DB 오류·sweeper 와의 레이스로 이미 전이됨)해도 예외를 흡수한다.
        // 일시 DB 오류(데드락·lock timeout)면 추출 재실행 없이 전이 write 만 짧게 재시도한다(TransitionRetry).
        runCatching { transitionRetry.execute { itemParsingService.markReady(snapshotId, snapshot) } }
            .onSuccess {
                log.info(
                    "item.parse.result item={} result={} reason={} latency={}ms url={}",
                    itemId,
                    ItemParsingMetrics.RESULT_READY,
                    ItemParsingMetrics.REASON_NONE,
                    elapsedMs,
                    link.safeLogString(),
                )
                ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_READY, ItemParsingMetrics.REASON_NONE)
            }
            .onFailure { e ->
                // 추출은 됐으나 값을 신뢰할 수 없어 READY 로 채울 수 없는 경우 → PROCESSING 방치 대신 FAILED 로.
                log.warn(
                    "item.parse.result item={} result={} reason={} url={}",
                    itemId,
                    ItemParsingMetrics.RESULT_FAILED,
                    ItemParsingMetrics.REASON_READY_REJECTED,
                    link.safeLogString(),
                )
                // 예외 상세(스택)는 별도 줄로 — 구조화(item.parse.result) 줄에 스택을 붙이면 logfmt 파싱이 깨진다.
                log.warn("item.parse.error item={} reason={} READY 전이 거부", itemId, ItemParsingMetrics.REASON_READY_REJECTED, e)
                markFailedQuietly(itemId, snapshotId)
                ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ItemParsingMetrics.REASON_READY_REJECTED)
            }
    }

    // 파싱 실패는 두 갈래다 — 재시도해도 결정론적으로 재실패하는 영구 오류는 즉시 종결, 일시 오류는 recover 에 맡긴다.
    // 판정은 ErrorCategory 가 쥔다: RETRYABLE(일시)만 PROCESSING 으로 두고, 그 외(INVALID_INPUT·SERVER_ERROR 등
    // 재시도 무의미)는 즉시 FAILED. HttpMappable 이 아닌 예상 못한 예외는 일시·영구를 단정할 수 없어 보수적으로 일시로 둔다.
    private fun onExtractFailed(
        itemId: Long,
        snapshotId: Long,
        link: ProductLink,
        e: Throwable,
    ) {
        if (isRetryable(e)) {
            // 일시 외부 오류(네트워크·timeout·5xx 게이트웨이 등) — 다시 하면 될 수도 있으므로 FAILED 로 종결하지 않고
            // PROCESSING 그대로 둔다. recover 가 stale 로 잡아 상한까지 재실행한다(execution at-least-once, #461).
            // 종결이 아니라 메트릭은 여기서 세지 않고(recover 가 종결 시 retry_exhausted/ready 로 집계, 중복 방지),
            // 풀 stack_trace 대신 logfmt 한 줄만 남긴다. 추출 실패 상세는 extractor 서비스가 같은 traceId 로 남기지만,
            // 재시도 로그만으로도 일시 오류 종류(network·timeout·5xx 등)를 분류할 수 있게 errorType·category·status 는 남긴다.
            val mappable = e as? HttpMappable
            log.warn(
                "item.parse.retry item={} url={} errorType={} category={} status={}",
                itemId,
                link.safeLogString(),
                e::class.simpleName,
                mappable?.category,
                mappable?.httpStatus?.value(),
            )
            return
        }
        // 확정 실패 — 상품 아님·추출값 신뢰 불가·호스트 차단·4xx 접근 불가 등. 같은 URL 을 다시 파싱해도 결과가
        // 같으므로 즉시 FAILED 로 종결한다(사용자에게 빨리 알림). 클라이언트 입력 계약 위반이라 서버 입장에선 정상 동작(info).
        val reason = reasonOf(e)
        log.info(
            "item.parse.result item={} result={} reason={} url={}",
            itemId,
            ItemParsingMetrics.RESULT_FAILED,
            reason,
            link.safeLogString(),
        )
        // 실패 사유 원문(공백 포함 가능)은 별도 줄로 — 구조화 줄의 logfmt 필드 파싱을 깨지 않게 분리한다.
        log.info("item.parse.error item={} reason={} cause={}", itemId, reason, e.message)
        markFailedQuietly(itemId, snapshotId)
        ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, reason)
    }

    // 확정 실패의 메트릭 reason. 상품 아님·추출값 신뢰 불가(ProductSnapshotException)는 not_product 로 따로 센다
    // (대시보드에서 "상품 아님"을 구분). 그 외 재시도 무의미 오류(원격 422 확정 실패 — 호스트 차단·4xx 등의 원격 번역)는 permanent_error.
    private fun reasonOf(e: Throwable): String =
        when (e) {
            is ProductSnapshotException -> ItemParsingMetrics.REASON_NOT_PRODUCT
            else -> ItemParsingMetrics.REASON_PERMANENT_ERROR
        }

    // FAILED 전이도 sweeper 와의 레이스로 실패할 수 있어(이미 전이됨) 잡아 흡수한다. 일시 DB 오류는 짧게 재시도한다.
    private fun markFailedQuietly(
        itemId: Long,
        snapshotId: Long,
    ) {
        runCatching { transitionRetry.execute { itemParsingService.markFailed(snapshotId) } }
            .onFailure { e ->
                when (e) {
                    is IllegalStateException -> log.info("item {} 는 이미 전이됨, FAILED 처리 생략: {}", itemId, e.message)
                    else -> log.error("item {} FAILED 전이 실패, PROCESSING 방치 위험", itemId, e)
                }
            }
    }

    companion object {
        // 파싱 단건 트레이스 span 이름. 대시보드 트레이스 "아이템" 탭이 TraceQL `name = "item.parse"` 로 이걸 거른다.
        private const val PARSE_OBSERVATION = "item.parse"

        // 재시도(일시)로 볼지 판정. 치명적 JVM 오류(Error: OutOfMemory·StackOverflow 등)는 재시도해도 소용없고
        // runCatching 이 Throwable 을 다 잡아 여기로 들어오므로 먼저 제외한다(재시도 대상 아님, 즉시 종결). 분류 가능한
        // HttpMappable 은 category 로 가르고(RETRYABLE 만 재시도), 그 외 예상 못한 예외(NPE·IllegalStateException 등)는
        // 일시·영구를 단정할 수 없어 보수적으로 재시도 대상으로 둔다(즉시 FAILED 면 일시 오류를 영구로 오판해 사라지므로).
        // recover 가 상한(MAX_ATTEMPTS)까지만 재실행해 bounded 이고 #461 retry-first 기조와 맞는다. 순수 함수라 단위 테스트로 망라한다.
        internal fun isRetryable(e: Throwable): Boolean =
            when (e) {
                is Error -> false
                is HttpMappable -> e.category == ErrorCategory.RETRYABLE
                else -> true
            }
    }
}
