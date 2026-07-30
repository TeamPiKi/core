package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.ProductSnapshotException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

// itemParsingExecutor 스레드에서 이미지 파싱 한 건을 수행한다. 입력은 등록 시 S3 에 durable 적재한 raw object key —
// 그 key 로 원본을 다시 읽어 파싱하므로, 메모리 ByteArray 와 달리 재실행 시점에 원본이 살아 있다.
// 추출 자체(download→OCR→crop→결과 업로드)는 ImageSnapshotExtractor 경계(원격 extractor 호출) 뒤로
// 위임하고, 이 워커는 상태 전이·재시도 정책·raw 회수만 진다.
// 외부 호출은 트랜잭션 바깥에서 끝내고, 상태 전이 영속화만 ItemParsingService(@Transactional)에 위임한다.
//
// 결과는 셋으로 갈린다(AsyncItemParsingWorker 와 동일한 execution at-least-once 정책, #461):
//   - 성공 → READY. 파싱이 끝났으니 raw 원본을 회수(delete)한다.
//   - 확정 실패(상품 아님·추출값 신뢰 불가·READY 전이 거부) → 즉시 FAILED + raw 회수. 다시 해도 결과가 같다.
//   - 일시 외부 오류(원격 추출 서비스 5xx·연결 실패 등 RETRYABLE) → 소유권 반납(release, PROCESSING→PENDING). raw 는 보존하고 다음 tick 이 다시 집는다.
@Component
class AsyncImageParsingWorker(
    private val imageSnapshotExtractor: ImageSnapshotExtractor,
    private val imageStorage: ImageStorage,
    private val itemParsingService: ItemParsingService,
    private val transitionRetry: TransitionRetry,
    private val parsingHeartbeat: ParsingHeartbeat,
    private val meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
) : ImageParsingWorker {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.ITEM_PARSING_EXECUTOR)
    override fun parse(
        itemId: Long,
        snapshotId: Long,
        imageKey: String,
        expectedAttempt: Int,
    ) {
        // 링크 워커와 같은 이유로 파싱 한 건을 "item.parse" span 으로 묶는다 — 디스패처가 @Scheduled 라 들어오는
        // trace 가 없어, 여기서 열지 않으면 원격 추출 호출·DB 전이 span 이 부모 없는 파편으로 흩어진다
        // (부모 없는 JDBC 관측은 ObservationConfig 가 거부하므로 아예 사라진다).
        // 소유권 획득→등록→해제 뼈대는 guarded 가 쥔다. 획득에 실패하면 body 를 건너뛰고 스킵 로그만 남긴다 —
        // 특히 raw 원본 회수(deleteRaw)를 하지 않는다(소유권을 쥔 새 시도가 그 원본으로 재실행해야 하므로). deleteRaw 는 body 안에만 있다.
        Observation.createNotStarted(AsyncItemParsingWorker.PARSE_OBSERVATION, observationRegistry).observe {
            parsingHeartbeat.guarded(
                snapshotId,
                expectedAttempt,
                onOwnershipLost = {
                    log.info("item.parse.skip item={} snapshot={} type=image reason=ownership_lost expected={}", itemId, snapshotId, expectedAttempt)
                },
            ) { attempt ->
                runCatching { imageSnapshotExtractor.extract(imageKey) }
                    .onSuccess { snapshot -> onExtracted(itemId, snapshotId, imageKey, snapshot, attempt) }
                    .onFailure { e -> onExtractFailed(itemId, snapshotId, imageKey, e, attempt) }
            }
        }
    }

    private fun onExtracted(
        itemId: Long,
        snapshotId: Long,
        imageKey: String,
        snapshot: ProductSnapshot,
        attempt: Int,
    ) {
        // 일시 DB 오류(데드락·lock timeout)면 추출 재실행 없이 전이 write 만 짧게 재시도한다(TransitionRetry).
        runCatching { transitionRetry.execute { itemParsingService.markReady(snapshotId, snapshot, attempt) } }
            .onSuccess { applied ->
                // 좀비 폐기(소유권 상실)면 전이가 스킵된다 — 결과를 성공으로 세지 않고, **특히 raw 를 지우지 않는다**.
                // 재클레임된 새 시도가 바로 그 원본으로 재실행해야 하므로, 여기서 지우면 되살릴 입력을 잃는다.
                if (!applied) {
                    log.info("item {} 이미지 좀비 결과 — 전이·raw 회수 생략 (attempt={})", itemId, attempt)
                    return@onSuccess
                }
                log.info("item {} 이미지 파싱 완료 → READY", itemId)
                ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_READY, ItemParsingMetrics.REASON_NONE)
                deleteRawQuietly(imageKey)
            }
            .onFailure { e ->
                // 추출은 됐으나 값을 신뢰할 수 없어 READY 로 채울 수 없음 → PROCESSING 방치 대신 FAILED.
                log.warn("item {} READY 전이 거부 → FAILED: {}", itemId, e.message)
                // 종결이 실제로 적용됐을 때만 결과를 세고 raw 를 회수한다 (좀비 폐기·전이 실패면 원본을 보존).
                if (markFailedQuietly(itemId, snapshotId, attempt)) {
                    ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ItemParsingMetrics.REASON_READY_REJECTED)
                    deleteRawQuietly(imageKey)
                }
            }
    }

    // 파싱 실패는 두 갈래다 — 일시 오류는 소유권을 반납해 다시 집히게 하고, 확정 실패만 즉시 종결한다.
    // 판정은 ErrorCategory 가 쥔다: RETRYABLE(원격 추출 서비스 5xx·연결 실패 등)만 반납하고,
    // 그 외(비-HttpMappable 예외 포함)는 즉시 FAILED — 재시도해도 같은 결과인 코드 버그성 예외라 되살리지 않는다
    // (비-HttpMappable 취급이 링크 워커와 다른 이유는 companion 의 isRetryable 주석 참고).
    private fun onExtractFailed(
        itemId: Long,
        snapshotId: Long,
        imageKey: String,
        e: Throwable,
        attempt: Int,
    ) {
        if (isRetryable(e)) {
            // 일시 외부 오류 — FAILED 로 종결하지 않고 소유권을 반납해 다음 tick(1s)이 다시 집게 한다
            // (execution at-least-once, #461). **raw 는 보존한다** — 재실행이 바로 그 원본을 다시 읽어야 하므로
            // 반납 경로에는 deleteRaw 가 없다. 종결이 아니라 메트릭도 여기서 세지 않는다(종결 시점에 집계).
            val mappable = e as? HttpMappable
            log.warn(
                "item.parse.retry item={} type=image errorType={} category={} status={}",
                itemId,
                e::class.simpleName,
                mappable?.category,
                mappable?.httpStatus?.value(),
            )
            releaseQuietly(itemId, snapshotId, attempt)
            return
        }
        // 확정 실패 — 상품 아님·추출값 신뢰 불가 등. 다시 해도 결과가 같으니 즉시 FAILED + raw 회수.
        // 단 전이가 실제로 적용됐을 때만이다 — 좀비 폐기·전이 실패면 결과를 세지도, raw 를 지우지도 않는다.
        val reason = reasonOf(e)
        if (!markFailedQuietly(itemId, snapshotId, attempt)) return
        log.info("item.parse.result item={} type=image result={} reason={}", itemId, ItemParsingMetrics.RESULT_FAILED, reason)
        log.info("item.parse.error item={} reason={} cause={}", itemId, reason, e.message)
        ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, reason)
        deleteRawQuietly(imageKey)
    }

    private fun reasonOf(e: Throwable): String =
        when (e) {
            is ProductSnapshotException -> ItemParsingMetrics.REASON_NOT_PRODUCT
            else -> ItemParsingMetrics.REASON_PERMANENT_ERROR
        }

    // raw 원본 회수는 best-effort — 삭제 실패가 파싱 결과(이미 READY/FAILED 확정)를 되돌리지 않는다.
    // 회수 못 한 raw 와 recover 상한 FAILED·유실분은 items/raw/ S3 lifecycle 이 백업으로 만료한다.
    private fun deleteRawQuietly(imageKey: String) {
        runCatching { imageStorage.delete(imageKey) }
            .onFailure { e -> log.warn("raw 이미지 {} 회수 실패(lifecycle 이 만료): {}", imageKey, e.message) }
    }

    // 소유권 반납 — 실패해도 흡수한다(링크 워커의 releaseQuietly 와 같은 이유: 반납은 지연 단축이지 정합성 장치가 아니다).
    // 반납은 raw 를 지우지 않는다 — 다음 실행의 입력이기 때문이다.
    private fun releaseQuietly(
        itemId: Long,
        snapshotId: Long,
        attempt: Int,
    ) {
        runCatching { transitionRetry.execute { itemParsingService.release(snapshotId, attempt) } }
            .onFailure { e -> log.info("item {} 이미지 소유권 반납 생략 (이미 전이됨·소유권 상실): {}", itemId, e.message) }
    }

    // 일시 DB 오류는 짧게 재시도한다(TransitionRetry). 영구 오류·sweeper 레이스는 즉시 전파돼 아래에서 흡수된다.
    // 반환값 = 전이가 실제로 적용됐는지 (false: 좀비 폐기 또는 전이 실패). raw 회수 여부를 이 값으로 가른다.
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
        // AsyncItemParsingWorker.isRetryable 과 달리 비-HttpMappable 예외를 재시도하지 않는다 — 이미지 경로의 외부
        // 오류(원격 extractor 5xx·연결 실패)는 전부 HttpMappable(RETRYABLE)로 분류돼 오고, 그 밖은 코드 버그성이라 즉시
        // 종결한다. 원격 클라이언트의 계약 번역이 이 판정과 맞물리므로 테스트가 이 함수로 직접 단언한다(companion 공개 이유).
        internal fun isRetryable(e: Throwable): Boolean = e is HttpMappable && e.category == ErrorCategory.RETRYABLE
    }
}
