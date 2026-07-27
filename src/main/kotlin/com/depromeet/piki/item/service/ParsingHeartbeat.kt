package com.depromeet.piki.item.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

// 실행 중인 파싱 작업의 "살아 있음"을 주기적으로 DB 에 새겨, recover 의 stale 판정이 산 워커를 죽음으로 오판하지 않게 한다.
//
// 배경: recover 는 updated_at 이 오래 조용한 PROCESSING 을 "죽었다"고 보고 재실행한다. 예전엔 "단건 ≤60s" 라는 시간 추정에
// 기댔지만, (a) 한 시도의 실제 소요가 60s 를 넘거나 (b) 워커 풀 포화로 claim 후 큐 대기가 길면 산 작업이 stale 로 오판돼
// 중복 실행됐다. 이제 산 워커가 박동으로 updated_at 을 계속 갱신하므로, stale = "프로세스 죽음(박동 연속 누락)" 만 남는다.
//
// 3층 방어: 산 워커는 박동이 지키고(이 클래스), 소유권 잃은 좀비는 획득·박동의 0행 매치가 막고(ParsingOwnership),
// 무한 행잉은 절대 캡(5분)이 끊어 침묵시켜 recover 회수에 넘긴다.
//
// 레지스트리 키는 snapshotId 다. 큐 정체로 같은 snapshot 의 옛 시도와 새 시도가 잠깐 공존할 수 있어(재클레임),
// register 는 더 최신 시도(높은 attempt)를 유지하고, deregister 는 자기 attempt 의 등록만 지운다 — 옛 시도의 종료가
// 산 새 시도의 박동을 지우지 않게 한다. fencing 이 정합성을 보장하므로 레지스트리 경합은 효율만 건드린다.
@Component
class ParsingHeartbeat(
    private val parsingOwnership: ParsingOwnership,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // snapshotId → 이 실행의 박동 상태. attempt 는 fencing 토큰(이 시도의 소유권), registeredAt 은 절대 캡 기준 시각.
    private val registry = ConcurrentHashMap<Long, Beat>()

    data class Beat(
        val attempt: Int,
        val registeredAt: LocalDateTime,
    )

    // 워커가 parse() 시작 시 등록한다. 같은 snapshot 에 더 최신 시도(높은 attempt)가 이미 있으면 그걸 유지한다 —
    // 큐 정체로 옛 시도가 뒤늦게 등록해 산 새 시도의 박동을 덮어쓰는 것을 막는다.
    fun register(
        snapshotId: Long,
        attempt: Int,
    ) = trackFrom(snapshotId, attempt, LocalDateTime.now())

    // 워커 종료(finally). 내 attempt 의 등록만 지운다 — 다른(더 최신) 시도가 덮어쓴 상태면 그 등록을 남긴다.
    fun deregister(
        snapshotId: Long,
        attempt: Int,
    ) {
        registry.compute(snapshotId) { _, beat -> beat?.takeUnless { it.attempt == attempt } }
    }

    // 워커의 단건 실행을 소유권·박동 수명주기로 감싼다 — 두 워커(URL·이미지)가 복제하던 뼈대를 한 곳에 캡슐화한다.
    //   소유권 획득 → 등록 → 본문 → 해제(finally).
    // 획득에 실패하면(이미 남이 가져갔거나 종결) body 를 실행하지 않고 onOwnershipLost 를 부른다 — 워커는 ext 호출·부수효과 없이 스킵한다.
    // body 는 획득한 토큰(attempt)을 받아 이후의 박동·전이에 실어 나른다. Observation 래핑·스킵 로그·전이는 각 워커가 소유하므로 콜백으로 받는다.
    fun guarded(
        snapshotId: Long,
        expectedAttempt: Int,
        onOwnershipLost: () -> Unit,
        body: (attempt: Int) -> Unit,
    ) {
        // 시도 소모는 이 한 줄에서만 일어난다 — 실행에 실제로 진입할 때. 제출이 거부돼 여기 못 오면 예산도 안 준다.
        val attempt = parsingOwnership.acquire(snapshotId, expectedAttempt) ?: return onOwnershipLost()
        register(snapshotId, attempt)
        try {
            body(attempt)
        } finally {
            deregister(snapshotId, attempt)
        }
    }

    // 박동 루프 — 등록된 각 실행 중 작업의 updated_at 을 fenced touch 로 민다. stale 임계(60s) ≥ 이 주기(15s) x 3 + 여유라,
    // 산 워커는 박동을 연속으로 놓치지 않는 한 stale 로 오판되지 않는다.
    // 항목별 runCatching 으로 격리한다 — 한 항목의 touch 가 DB 블립으로 던져도 그 사이클의 나머지 항목 박동이 통째로 스킵되지 않는다.
    // 실패는 warn 만 남기고 넘어간다(레지스트리 유지 → 다음 사이클이 재시도).
    @Scheduled(fixedDelay = BEAT_INTERVAL_MS)
    fun beat() {
        val now = LocalDateTime.now()
        registry.forEach { (snapshotId, beat) ->
            runCatching { beatOne(snapshotId, beat, now) }
                .onFailure { e -> log.warn("snapshot {} 박동 실패 — 다음 사이클 재시도 (attempt={}): {}", snapshotId, beat.attempt, e.message) }
        }
    }

    private fun beatOne(
        snapshotId: Long,
        beat: Beat,
        now: LocalDateTime,
    ) {
        // 절대 캡 — 등록 후 이 시간이 지나도 안 끝난 작업(무한 행잉)은 갱신을 멈춰 침묵시킨다. 그러면 updated_at 이 굳어
        // recover 가 stale 로 회수한다(박동이 좀비를 영구 보호하는 것을 끊는다). 갱신 없이 레지스트리에서만 제거한다.
        if (Duration.between(beat.registeredAt, now) > ABSOLUTE_CAP) {
            registry.remove(snapshotId, beat)
            log.warn(
                "snapshot {} 박동 절대 캡({}분) 초과 — 갱신 중단, recover 회수에 맡김 (attempt={})",
                snapshotId,
                ABSOLUTE_CAP.toMinutes(),
                beat.attempt,
            )
            return
        }
        if (parsingOwnership.renew(snapshotId, beat.attempt) == 0) {
            // 0행 = 소유권 없음(재클레임됐거나 이미 READY/FAILED 로 전이). 좀비 박동을 멈춘다.
            registry.remove(snapshotId, beat)
            log.info("snapshot {} 박동 대상 아님(재클레임·이미 전이) — 레지스트리 제거 (attempt={})", snapshotId, beat.attempt)
        }
    }

    // 특정 등록 시각으로 seed 하는 내부 진입점. register 는 now 로, 타이밍 테스트는 과거 시각으로 절대 캡을 재현할 때 쓴다.
    internal fun trackFrom(
        snapshotId: Long,
        attempt: Int,
        registeredAt: LocalDateTime,
    ) {
        registry.merge(snapshotId, Beat(attempt, registeredAt)) { old, new ->
            if (new.attempt >= old.attempt) new else old
        }
    }

    // 테스트가 레지스트리 상태를 관측하는 최소 표면.
    internal fun isTracking(snapshotId: Long): Boolean = registry.containsKey(snapshotId)

    companion object {
        // 박동 주기. stale 임계(ItemParsingScheduler.STALE_TIMEOUT_SECONDS = 60s) ≥ 이 주기 x 3 + 여유를 지킨다.
        const val BEAT_INTERVAL_MS = 15_000L

        // 절대 캡 — 이보다 오래 안 끝난 실행은 박동을 멈춰 recover 가 회수하게 한다. 무한 행잉이 박동으로 영원히 사는 것을 막는다.
        // 값은 마감(ItemParsingScheduler.DEADLINE_MINUTES = 3분)에서 역산했다: 캡(2분) + stale 임계(60초) = 3분이라
        // "캡으로 침묵 → stale 회수" 경로가 마감 안에서 끝난다.
        private val ABSOLUTE_CAP = Duration.ofMinutes(2)
    }
}
