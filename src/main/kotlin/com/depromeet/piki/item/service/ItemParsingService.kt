package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseFailureReason
import com.depromeet.piki.item.domain.ParseRequest
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.event.ItemParsingIncomplete
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.repository.ParseRequestRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// 파싱 결과의 상태 전이만 짧은 트랜잭션으로 영속화한다 (전이는 dirty checking 으로 커밋 시 반영).
// 외부 호출(extract)은 워커가 트랜잭션 바깥에서 끝낸다. 워커(@Async)·디스패처(@Scheduled)와 별도 빈으로 두어
// AOP proxy 를 거치게 한다(self-invocation 회피).
//
// 작업 큐는 parse_requests 다(#1073 2단계). 집기·회수·마감·소유권 판정이 전부 요청 행에 달려 있고, 버전은 결과값의
// 그릇이다. 버전의 status 를 아직 함께 미는 이유는 카드 표시값이 그걸 읽기 때문이고, 4단계에서 표시값이 요청을
// 보게 되면 아래 syncSnapshot* 묶음과 함께 사라진다.
@Service
class ItemParsingService(
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val parseRequestRepository: ParseRequestRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 반환값은 **이 전이로 확정된 상태** 다. null(좀비 폐기)이면 호출부는 자기 결과를 반영된 것으로 세면 안 된다 —
    // 특히 이미지 워커의 raw 원본 회수는 반드시 이 값으로 막아야 한다(소유권을 쥔 새 시도가 그 원본으로 재실행하므로).
    // 상태가 셋으로 갈리는(READY/INCOMPLETE/FAILED) 판정은 도메인(ItemSnapshot.markExtracted)이 쥐고, 여기서는
    // 그 결과에 맞는 도메인 사실을 발행하기만 한다(#944).
    @Transactional
    fun markExtracted(
        requestId: Long,
        snapshot: ProductSnapshot,
        expectedAttempt: Int,
    ): ItemStatus? {
        // 요청을 FOR UPDATE 로 먼저 잡아 fence 검사와 두 행의 쓰기를 한 락 구간으로 원자화한다.
        // 락 순서(요청 다음 버전)는 두 행을 함께 쓰는 모든 경로가 공유한다.
        val request = lockRequest(requestId)
        if (isZombieResult(request, expectedAttempt)) return null
        val target = lockResultSnapshot(request)
        val status = target.markExtracted(snapshot)
        if (status == ItemStatus.FAILED) request.fail(ParseFailureReason.EXTRACTION) else request.succeed()
        // 트랜잭션 안에서 발행 → AFTER_COMMIT 리스너가 커밋 성공 후에만 알림을 보낸다 (롤백 시 발송 안 됨). itemId 는 snapshot 단일 출처.
        eventPublisher.publishEvent(parsingFact(status, target))
        return status
    }

    // 확정된 상태에 대응하는 도메인 사실. markExtracted 는 셋 중 하나로만 끝나므로 나머지 상태는 도메인 분기가 깨진
    // 코드 버그다(500). 사실을 상태와 1:1 로 두는 이유는 소비자(알림·SSE)가 상태를 다시 해석하지 않게 하려는 것이다.
    private fun parsingFact(
        status: ItemStatus,
        target: ItemSnapshot,
    ): Any =
        when (status) {
            ItemStatus.READY -> ItemParsingCompleted(target.itemId, target.getId())
            ItemStatus.INCOMPLETE -> ItemParsingIncomplete(target.itemId, target.getId())
            ItemStatus.FAILED -> ItemParsingFailed(target.itemId, target.getId())
            ItemStatus.PENDING, ItemStatus.PROCESSING -> error("추출 전이가 만들 수 없는 상태 $status")
        }

    // markExtracted 와 같이 적용 여부를 돌려준다 (false = 좀비 폐기).
    @Transactional
    fun markFailed(
        requestId: Long,
        expectedAttempt: Int,
    ): Boolean {
        val request = lockRequest(requestId)
        if (isZombieResult(request, expectedAttempt)) return false
        val target = lockResultSnapshot(request)
        syncSnapshotFailed(target)
        request.fail(ParseFailureReason.EXTRACTION)
        eventPublisher.publishEvent(ItemParsingFailed(target.itemId, target.getId()))
        return true
    }

    // 소유권 반납 — 일시 외부 오류로 이번 실행이 결론 없이 끝났을 때 워커가 부른다. PROCESSING → PENDING 으로 되돌려
    // 디스패처가 다음 tick(1s)에 곧바로 다시 집게 한다.
    //
    // 반납이 없으면 다음 실행은 stale 판정(마지막 박동 + 임계 60s)을 기다려야 한다. 박동이 "산 워커를 지키는" 대가로
    // 죽음 감지가 워커 사망 시각 기준으로 밀렸기 때문인데, 실행이 스스로 끝났다는 걸 아는 그 순간 반납하면 그 지연이 사라진다.
    // 그 결과 stale 되살림은 본래 의미(**프로세스가 죽어 아무도 반납해 주지 못한 경우**)만 남는다.
    //
    // 반환값은 markExtracted/markFailed 와 같은 계약이다 — false 면 좀비라 아무것도 반영되지 않았다.
    @Transactional
    fun release(
        requestId: Long,
        expectedAttempt: Int,
    ): Boolean {
        val request = lockRequest(requestId)
        if (isZombieResult(request, expectedAttempt)) return false
        val target = lockResultSnapshot(request)
        // 예산을 다 쓴 실행을 반납하면 무한 재큐잉이 된다 — 되살림 경로와 같은 판정·같은 reason 으로 여기서 종결한다.
        if (request.attemptCount >= MAX_ATTEMPTS) {
            syncSnapshotFailed(target)
            request.fail(ParseFailureReason.RETRY_EXHAUSTED)
            eventPublisher.publishEvent(ItemParsingFailed(target.itemId, target.getId()))
            ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ItemParsingMetrics.REASON_RETRY_EXHAUSTED)
            // 워커 스레드(observation 스코프)에서 불리므로 이 라인엔 trace_id 가 붙는다. url 은 마지막 시도의
            // item.parse.retry 라인·트레이스로 본다(락 구간에서 item 로드를 피한다).
            logTerminalFailure(request.itemId, ItemParsingMetrics.REASON_RETRY_EXHAUSTED, link = null)
            return true
        }
        syncSnapshotRelease(target)
        request.release()
        return true
    }

    // fencing — 로드한 요청의 attemptCount 가 워커가 획득한 토큰(expectedAttempt)과 어긋나면, 실행 도중 소유권이
    // 다른 시도로 넘어간 좀비 워커의 결과다. 전이 없이 폐기(로그만)해, 옛 시도가 새 시도의 행을 오전이·오종결하지 못하게 한다. true=폐기.
    // recover 내부의 FAILED 종결·마감 종결은 소유권 회수 행위 자체라 이 fencing 을 타지 않는다(entity 전이를 직접 호출한다).
    private fun isZombieResult(
        request: ParseRequest,
        expectedAttempt: Int,
    ): Boolean {
        if (request.attemptCount == expectedAttempt) return false
        log.info(
            "item {} request {} 좀비 결과 폐기 — 소유권 attempt 불일치(expected={} actual={})",
            request.itemId,
            request.getId(),
            expectedAttempt,
            request.attemptCount,
        )
        return true
    }

    // 디스패처가 PENDING 요청을 집어 PROCESSING 으로 claim 한다 (짧은 트랜잭션 + FOR UPDATE).
    // 실제 파싱(외부 LLM, 트랜잭션 밖)은 디스패처가 반환받은 ClaimedItem 으로 워커에 넘긴다.
    //
    // batch 전체가 한 트랜잭션이므로 한 행에서 throw 하면 batch 전체가 롤백되고, FIFO 라 같은 선두 batch 가
    // 매 tick 재fetch 돼 poison-pill 로 디스패치가 영구 정지한다. 따라서 이상 행도 throw 없이 처리한다:
    //   - 요청은 FOR UPDATE 로 PENDING 으로 잠겨 있어 claim 은 throw 하지 않는다.
    //   - 버전 쪽 전이는 tolerant 하게 민다(아래 syncSnapshot* 주석).
    //   - 입력(link·imageKey)이 없는 요청은 워커에 안 넘긴다 → recover 가 stale 로 잡아 FAILED 로 종결한다.
    @Transactional
    fun claimDuePending(batchSize: Int): List<ClaimedItem> {
        val requests = parseRequestRepository.findDuePending(batchSize)
        if (requests.isEmpty()) return emptyList()
        // per-request N+1 대신 item·버전을 한 번에 로드한다 (요청은 itemId 만 들고 입력(link/imageKey)은 item 소관).
        val itemById = itemRepository.findByIds(requests.map { it.itemId }).associateBy { it.getId() }
        val snapshotById = snapshotsOf(requests)
        return requests.mapNotNull { request ->
            request.claim()
            syncSnapshotClaim(snapshotById[request.resultSnapshotId])
            // claim 은 attemptCount 를 건드리지 않으므로 현재값이 곧 "획득 시 기대하는 직전 값"이다.
            toClaim(request, itemById[request.itemId], request.attemptCount)
        }
    }

    // stale PROCESSING(프로세스가 죽어 박동이 끊긴 요청)을 집어 재실행 또는 종결한다.
    // claim-at-least-once 를 execution at-least-once 로 끌어올리는 핵심(#461) — 기존의 "무조건 FAILED" 를 "재실행 우선"으로 바꿨다.
    //
    // stale 판정은 heartbeatAt 기준이다. 집기가 이 시계를 시작하고, 산 워커는 ParsingHeartbeat 가 계속 밀며, 일시 오류로
    // 스스로 끝난 실행은 release 로 즉시 반납하므로, heartbeatAt 이 threshold 보다 오래됐다는 건 "박동이 연속으로 끊겼는데
    // 반납도 없었다 = 프로세스가 죽었다" 는 뜻이다.
    // 그런 요청을:
    //   - link·imageKey 가 둘 다 없으면(입력 없는 orphan) 되살릴 수 없으므로 즉시 FAILED. 이미지(imageKey)는 S3 raw 로 durable 해 link 처럼 재실행한다.
    //   - attempt 가 실행 상한(MAX_ATTEMPTS)에 도달했으면 더 시도하지 않고 FAILED (무한 재큐잉 방지).
    //   - 그 외에는 되살림 대상으로 지목해 반환한다(DB 는 그대로) — 워커 제출은 스케줄러가 트랜잭션 밖에서 하고, attempt 는 워커가 실행에 진입할 때 소모한다.
    //
    // 되살림은 reviveSlots(호출부의 가용 워커 슬롯)만큼만 지목한다 — 제출도 못 할 지목은 로그만 늘린다. 미룬 행은
    // 아무것도 안 건드리므로 다음 사이클이 그대로 다시 집는다. 반면 **종결(FAILED)은 reviveSlots 와 무관하게 진행**한다 —
    // 워커 슬롯이 필요 없는 판정이라, 슬롯으로 막으면 풀이 오래 포화일 때 종결이 영영 밀린다.
    @Transactional
    fun reviveOrFailStale(
        threshold: LocalDateTime,
        batchSize: Int,
        reviveSlots: Int,
    ): StaleProcessingOutcome {
        val stale = parseRequestRepository.findStaleProcessing(threshold, batchSize)
        if (stale.isEmpty()) return StaleProcessingOutcome(emptyList(), 0)
        val itemById = itemRepository.findByIds(stale.map { it.itemId }).associateBy { it.getId() }
        val snapshotById = snapshotsOf(stale)
        val toRevive = mutableListOf<ClaimedItem>()
        var failedCount = 0
        stale.forEach { request ->
            // 되살릴 입력(link/imageKey)이 없으면(둘 다 부재 = orphan, 또는 item 부재) 종결. toClaim 이 null 로 일괄 판정한다.
            // 지목은 attemptCount 를 안 올리므로 현재값을 그대로 실어 보낸다 — 워커가 실행에 진입하며 이 값으로 +1 을 시도한다.
            val claim =
                toClaim(request, itemById[request.itemId], request.attemptCount) ?: run {
                    terminate(request, snapshotById, ParseFailureReason.NO_SOURCE, itemById)
                    failedCount++
                    return@forEach
                }
            // 실행 상한 도달: 더 되살리지 않고 종결.
            if (request.attemptCount >= MAX_ATTEMPTS) {
                terminate(request, snapshotById, ParseFailureReason.RETRY_EXHAUSTED, itemById)
                failedCount++
                return@forEach
            }
            // 가용 슬롯 소진: 이번 사이클엔 지목하지 않는다. 다음 사이클이 같은 행을 다시 집는다.
            if (toRevive.size >= reviveSlots) return@forEach
            // 되살림 = 지목뿐. DB 는 건드리지 않는다 — 소유권(attempt)은 워커가 실행에 진입할 때 스스로 가져간다.
            toRevive.add(claim)
        }
        return StaleProcessingOutcome(toRevive, failedCount)
    }

    // 마감(deadline) 초과 종결 — createdAt 이 threshold 이전인 비-터미널 요청을 FAILED 로 끝낸다. 종결한 건수를 반환한다.
    //
    // attempt 예산과 별개인 **벽시계** 판정이다. attempt 는 "실행을 몇 번 했나"(예산)를, 이 마감은 "얼마나 오래 끌 수 있나"를
    // 답한다. 예전엔 이 둘이 한 숫자에 얽혀 있어서, 실행하지도 않은 제출 거부가 종결 시점까지 앞당기는 불공정이 있었다.
    // 마감은 박동과도 무관해 "박동은 멀쩡한데 너무 느린 실행"도, "슬롯이 없어 집히지 못한 PENDING"도 함께 종결한다.
    @Transactional
    fun failOverdue(
        threshold: LocalDateTime,
        batchSize: Int,
    ): Int {
        val overdue = parseRequestRepository.findOverdue(threshold, batchSize)
        if (overdue.isEmpty()) return 0
        // 종결 로그에 url 을 실으려는 item 로드 — 정상 사이클은 위 early return 으로 여기 안 오므로 비용이 없다.
        val itemById = itemRepository.findByIds(overdue.map { it.itemId }).associateBy { it.getId() }
        val snapshotById = snapshotsOf(overdue)
        overdue.forEach { request ->
            terminate(request, snapshotById, ParseFailureReason.DEADLINE, itemById)
        }
        return overdue.size
    }

    // recover 계열의 종결 한 벌 — 요청과 버전을 함께 끝내고 사실·메트릭·로그를 남긴다.
    // 메트릭 사유는 종결 사유에서 파생한다. 둘을 따로 받으면 호출부마다 짝을 맞춰야 해 어긋날 자리가 생긴다.
    private fun terminate(
        request: ParseRequest,
        snapshotById: Map<Long, ItemSnapshot>,
        reason: ParseFailureReason,
        itemById: Map<Long, Item>,
    ) {
        val snapshot = snapshotById[request.resultSnapshotId]
        syncSnapshotFailed(snapshot)
        request.fail(reason)
        snapshot?.let { eventPublisher.publishEvent(ItemParsingFailed(it.itemId, it.getId())) }
        val metricReason = metricReasonOf(reason)
        ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, metricReason)
        logTerminalFailure(request.itemId, metricReason, itemById[request.itemId]?.link)
    }

    private fun metricReasonOf(reason: ParseFailureReason): String =
        when (reason) {
            ParseFailureReason.NO_SOURCE -> ItemParsingMetrics.REASON_NO_SOURCE
            ParseFailureReason.RETRY_EXHAUSTED -> ItemParsingMetrics.REASON_RETRY_EXHAUSTED
            ParseFailureReason.DEADLINE -> ItemParsingMetrics.REASON_DEADLINE
            ParseFailureReason.EXTRACTION -> ItemParsingMetrics.REASON_EXTRACT_QUALITY
        }

    private fun snapshotsOf(requests: List<ParseRequest>): Map<Long, ItemSnapshot> =
        itemSnapshotRepository.findByIds(requests.mapNotNull { it.resultSnapshotId }).associateBy { it.getId() }

    private fun lockRequest(requestId: Long): ParseRequest =
        parseRequestRepository.findByIdForUpdate(requestId) ?: error("파싱 대상 request $requestId 이 없다")

    private fun lockResultSnapshot(request: ParseRequest): ItemSnapshot {
        val snapshotId = request.resultSnapshotId ?: error("request ${request.getId()} 에 결과 버전이 없다")
        return itemSnapshotRepository.findByIdForUpdate(snapshotId) ?: error("파싱 대상 snapshot $snapshotId 이 없다")
    }

    // --- 버전 status 미러링 (4단계에서 표시값이 요청을 보게 되면 이 묶음째 삭제) ---
    // 전이 가드를 check 로 강제하지 않고 현재 상태를 보고 건너뛴다. 배포 교체 구간에는 옛 컨테이너가 버전 테이블을
    // 직접 집으므로 버전이 이미 옮겨가 있을 수 있고, 거기서 throw 하면 batch 가 통째로 롤백돼 디스패치가 멈춘다.
    private fun syncSnapshotClaim(snapshot: ItemSnapshot?) {
        if (snapshot?.status == ItemStatus.PENDING) snapshot.markProcessing()
    }

    private fun syncSnapshotRelease(snapshot: ItemSnapshot?) {
        if (snapshot?.status == ItemStatus.PROCESSING) snapshot.release()
    }

    private fun syncSnapshotFailed(snapshot: ItemSnapshot?) {
        snapshot ?: return
        if (snapshot.status == ItemStatus.PENDING || snapshot.status == ItemStatus.PROCESSING) snapshot.expire()
    }

    // recover 경로 FAILED 종결의 구조화 결과 로그 — 워커의 item.parse.result 라인과 같은 logfmt 계약이다.
    // 알림·대시보드가 이 한 줄 == 종결 1건으로 세므로, FAILED 전이를 새로 만들면 반드시 함께 남긴다(#902).
    // 레벨은 warn — 클라이언트 잘못(워커의 확정 실패, info)과 달리 파이프라인이 끝내 못 끝낸 서버측 문제라서다.
    private fun logTerminalFailure(
        itemId: Long,
        reason: String,
        link: ProductLink?,
    ) {
        link ?: run {
            log.warn("item.parse.result item={} result={} reason={}", itemId, ItemParsingMetrics.RESULT_FAILED, reason)
            return
        }
        log.warn("item.parse.result item={} result={} reason={} url={}", itemId, ItemParsingMetrics.RESULT_FAILED, reason, link.safeLogString())
    }

    // 요청의 item 입력(link XOR imageKey)으로 claim 객체를 만든다. link 우선, 없으면 imageKey, 둘 다 없으면
    // (입력 없는 orphan 또는 item 부재) null — claim 경로는 워커에 안 넘기고(다음 recover 가 stale 로 잡아 FAILED),
    // recover 경로는 즉시 FAILED 한다. 정상 흐름(URL·이미지 등록)엔 항상 입력이 있어 null 은 영속화 경로가 깨진 신호다.
    // expectedAttempt 는 소유권 획득 시 기대하는 직전 attemptCount — 워커가 이 값으로 조건부 +1 을 시도한다.
    private fun toClaim(
        request: ParseRequest,
        item: Item?,
        expectedAttempt: Int,
    ): ClaimedItem? {
        val requestId = request.getId()
        val snapshotId =
            request.resultSnapshotId ?: run {
                log.error("request {} 에 결과 버전이 없어 claim 제외", requestId)
                return null
            }
        val resolved =
            item ?: run {
                log.error("request {} 의 item {} 이 없어 claim 제외", requestId, request.itemId)
                return null
            }
        resolved.link?.let { return LinkClaim(request.itemId, requestId, snapshotId, it, expectedAttempt) }
        resolved.sourceImageKey?.let { return ImageClaim(request.itemId, requestId, snapshotId, it, expectedAttempt) }
        log.error("request {} (item {}) 에 link·imageKey 둘 다 없어 claim 제외 (입력 없는 orphan)", requestId, request.itemId)
        return null
    }

    companion object {
        // **실행** 시도 상한(초회 1 + 재실행 1). 집기(claim)·되살림(revive)이 아니라 워커가 실행에 진입할 때만 소모되므로,
        // 제출이 거부돼 실행이 0회인 행은 이 예산을 잃지 않는다.
        //
        // 예산(실행 횟수)과 마감(시간)은 서로 다른 질문에 답한다 — "얼마나 오래 끌 수 있나"는 이 값이 아니라
        // ItemParsingScheduler.DEADLINE_MINUTES 가 답한다. 상한을 소진하는 두 경로(반납 release·되살림 revive)가
        // 같은 판정을 써야 하므로 스케줄러가 아니라 이 서비스가 정본을 쥔다.
        const val MAX_ATTEMPTS = 2
    }
}
