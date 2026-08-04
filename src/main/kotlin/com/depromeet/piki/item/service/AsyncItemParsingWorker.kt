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
// 일시 외부 오류 → 소유권 반납(release, PROCESSING→PENDING)해 다음 tick 이 다시 집게 한다.
// 반납은 "이 실행은 결론 없이 끝났다"는 **사실 통지**일 뿐이고, 재시도할지 종결할지의 **정책은 여전히 서비스가 쥔다**
// (실행 예산이 남았으면 PENDING 으로 되돌리고, 소진했으면 그 자리에서 FAILED).
// 전이 호출(markReady/markFailed)은 runCatching 으로 감싸 워커 스레드로 예외가 새지 않게 한다
// (recover 와의 레이스로 이미 전이됐거나, 추출값이 도메인 불변식을 위반하는 경우).
@Component
class AsyncItemParsingWorker(
    private val productLinkExtractor: ProductLinkExtractor,
    private val itemParsingService: ItemParsingService,
    private val itemIdentityRecorder: ItemIdentityRecorder,
    private val transitionRetry: TransitionRetry,
    private val parsingHeartbeat: ParsingHeartbeat,
    private val meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
) : ItemParsingWorker {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.ITEM_PARSING_EXECUTOR)
    override fun parse(
        itemId: Long,
        snapshotId: Long,
        link: ProductLink,
        expectedAttempt: Int,
    ) {
        // 파싱 한 건을 "item.parse" span 하나로 묶는다 — 원격 extractor 호출이 그 자식 span 으로 붙고, extractor 내부의
        // fetch·structured·LLM span 은 traceparent 전파로 그 아래 이어져, 단건 파이프라인을 크로스서비스로 끝까지 펼쳐 볼 수 있다.
        // 디스패처가 @Scheduled 라 들어오는 trace 가 없어, 여기서 만들지 않으면 원격 호출 span 이 따로 떠 묶이지 않는다.
        // 소유권 획득→등록→해제 뼈대는 guarded 가 쥔다. 획득에 실패하면 body 를 건너뛰고 스킵 로그만 남긴다(ext 호출·부수효과 없음).
        Observation.createNotStarted(PARSE_OBSERVATION, observationRegistry).observe {
            parsingHeartbeat.guarded(
                snapshotId,
                expectedAttempt,
                onOwnershipLost = {
                    log.info("item.parse.skip item={} snapshot={} reason=ownership_lost expected={}", itemId, snapshotId, expectedAttempt)
                },
            ) { attempt ->
                val started = System.nanoTime()
                runCatching { productLinkExtractor.extract(link) }
                    .onSuccess { snapshot -> onExtracted(itemId, snapshotId, link, snapshot, started, attempt) }
                    .onFailure { e -> onExtractFailed(itemId, snapshotId, link, e, attempt) }
            }
        }
    }

    private fun onExtracted(
        itemId: Long,
        snapshotId: Long,
        link: ProductLink,
        snapshot: ProductSnapshot,
        started: Long,
        attempt: Int,
    ) {
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // 전이가 실패(추출값 도메인 검증 위반·DB 오류·sweeper 와의 레이스로 이미 전이됨)해도 예외를 흡수한다.
        // 일시 DB 오류(데드락·lock timeout)면 추출 재실행 없이 전이 write 만 짧게 재시도한다(TransitionRetry).
        runCatching { transitionRetry.execute { itemParsingService.markReady(snapshotId, snapshot, attempt) } }
            .onSuccess { applied ->
                // 좀비 폐기(소유권 상실)면 이 워커의 결과는 반영되지 않았다 — 결과 원장(로그·메트릭)에 성공으로 세지 않는다.
                // 폐기 사유 자체는 서비스가 남긴다.
                if (!applied) return@onSuccess
                log.info(
                    "item.parse.result item={} result={} reason={} latency={}ms url={}",
                    itemId,
                    ItemParsingMetrics.RESULT_READY,
                    ItemParsingMetrics.REASON_NONE,
                    elapsedMs,
                    link.safeLogString(),
                )
                ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_READY, ItemParsingMetrics.REASON_NONE)
                // 정체성 기록(#825 관측 단계) — READY 전이가 커밋된 뒤 별도 트랜잭션으로 canonical·별칭을 남긴다.
                // 전이와 분리하는 이유·병합 시 원자화 계획은 recorder 주석 참고. 기록 실패가 파싱 결과를 해치면
                // 안 되므로 예외를 흡수한다(관측 부가 기능).
                runCatching { itemIdentityRecorder.recordParsingIdentity(itemId, snapshot.finalUrl) }
                    .onFailure { e -> log.warn("item.identity.error item={} 정체성 기록 실패", itemId, e) }
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
                if (markFailedQuietly(itemId, snapshotId, attempt)) {
                    ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ItemParsingMetrics.REASON_READY_REJECTED)
                }
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
        attempt: Int,
    ) {
        if (isRetryable(e)) {
            // 일시 외부 오류(네트워크·timeout·5xx 게이트웨이 등) — 다시 하면 될 수도 있으므로 FAILED 로 종결하지 않고
            // 소유권을 반납해 다음 tick(1s)이 곧바로 다시 집게 한다(execution at-least-once, #461).
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
            releaseQuietly(itemId, snapshotId, attempt)
            return
        }
        // 확정 실패 — 상품 아님·추출값 신뢰 불가·호스트 차단·4xx 접근 불가 등. 같은 URL 을 다시 파싱해도 결과가
        // 같으므로 즉시 FAILED 로 종결한다(사용자에게 빨리 알림). 클라이언트 입력 계약 위반이라 서버 입장에선 정상 동작(info).
        val reason = reasonOf(e)
        // 전이가 실제로 적용됐을 때만 결과를 원장에 남긴다 — 좀비 폐기·전이 실패면 이 워커의 결과는 반영되지 않았다.
        if (!markFailedQuietly(itemId, snapshotId, attempt)) return
        log.info(
            "item.parse.result item={} result={} reason={} url={}",
            itemId,
            ItemParsingMetrics.RESULT_FAILED,
            reason,
            link.safeLogString(),
        )
        // 실패 사유 원문(공백 포함 가능)은 별도 줄로 — 구조화 줄의 logfmt 필드 파싱을 깨지 않게 분리한다.
        log.info("item.parse.error item={} reason={} cause={}", itemId, reason, e.message)
        ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, reason)
    }

    // 확정 실패의 메트릭 reason. 상품 아님·추출값 신뢰 불가(ProductSnapshotException)는 not_product 로 따로 센다
    // (대시보드에서 "상품 아님"을 구분). 그 외 재시도 무의미 오류(원격 422 확정 실패 — 호스트 차단·4xx 등의 원격 번역)는 permanent_error.
    private fun reasonOf(e: Throwable): String =
        when (e) {
            is ProductSnapshotException -> ItemParsingMetrics.REASON_NOT_PRODUCT
            else -> ItemParsingMetrics.REASON_PERMANENT_ERROR
        }

    // 소유권 반납 — 실패해도 흡수한다. 반납은 **지연 단축 장치이지 정합성 장치가 아니다**: 반납이 안 되면 그 행은
    // 예전처럼 stale 회수(마지막 박동 + 60s)가 늦게라도 잡고, 그래도 안 되면 마감이 끊는다. 그래서 여기서 던지지 않는다.
    // 레이스로 이미 마감 종결됐거나 소유권을 잃었으면 서비스가 false 를 주거나 entity check 가 던지고, 둘 다 정상 상황이다.
    private fun releaseQuietly(
        itemId: Long,
        snapshotId: Long,
        attempt: Int,
    ) {
        runCatching { transitionRetry.execute { itemParsingService.release(snapshotId, attempt) } }
            .onFailure { e -> log.info("item {} 소유권 반납 생략 (이미 전이됨·소유권 상실): {}", itemId, e.message) }
    }

    // FAILED 전이도 sweeper 와의 레이스로 실패할 수 있어(이미 전이됨) 잡아 흡수한다. 일시 DB 오류는 짧게 재시도한다.
    // 반환값 = 전이가 실제로 적용됐는지 (false: 좀비 폐기 또는 전이 실패).
    private fun markFailedQuietly(
        itemId: Long,
        snapshotId: Long,
        attempt: Int,
    ): Boolean =
        runCatching { transitionRetry.execute { itemParsingService.markFailed(snapshotId, attempt) } }
            .onFailure { e ->
                when (e) {
                    is IllegalStateException -> log.info("item {} 는 이미 전이됨, FAILED 처리 생략: {}", itemId, e.message)
                    else -> log.error("item {} FAILED 전이 실패, PROCESSING 방치 위험", itemId, e)
                }
            }.getOrDefault(false)

    companion object {
        // 파싱 단건 트레이스 span 이름. 대시보드 트레이스 "아이템" 탭이 TraceQL `name = "item.parse"` 로 이걸 거른다.
        // 이미지 파싱(AsyncImageParsingWorker)도 같은 이름을 공유한다 — 대시보드 필터가 링크·이미지를 한 탭으로 본다.
        internal const val PARSE_OBSERVATION = "item.parse"

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
