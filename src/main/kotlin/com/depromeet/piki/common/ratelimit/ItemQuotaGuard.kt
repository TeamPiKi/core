package com.depromeet.piki.common.ratelimit

import com.depromeet.piki.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

// 아이템 등록 경로가 부르는 한도 게이트(#339·#927).
//
// 인터셉터가 아니라 서비스가 직접 부르는 이유 둘: (1) 차감 주체가 요청자가 아닐 수 있다 — 토너먼트 축은
// tournamentId 로 오너를 찾아야 알 수 있어 핸들러 진입 시점엔 모른다. (2) 차감량이 요청 내용에 달렸다 —
// 이미지 장수만큼 깎아야 하는데 인터셉터에서 multipart 를 파싱해 세는 것은 본문을 두 번 읽는 일이다.
@Component
class ItemQuotaGuard(
    private val store: RedisItemQuotaStore,
    private val properties: ItemQuotaProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 두 축을 함께 확인하고, 통과하면 그만큼 차감한 뒤 반환한다.
    //   - 요청자 몫 소진 → ItemQuotaException(429). errorCode 는 호출 도메인이 넘긴다 — 사용자에게 보일 문구와
    //     code 의 소유권은 도메인에 있다.
    //   - 전역 가용량 소진 → ItemQuotaException(503). 어느 도메인에서 닿든 원인이 같아 공통 code 를 쓴다.
    fun consume(
        scope: ItemQuotaScope,
        ownerId: UUID,
        amount: Int,
        errorCode: ErrorCode,
    ) {
        if (!properties.enabled) return

        val verdict =
            try {
                store.tryConsume(
                    ownerKey = scope.keyPrefix + ownerId,
                    capacityKey = RedisItemQuotaStore.CAPACITY_KEY,
                    amount = amount,
                    ownerLimit = properties.limitOf(scope),
                    capacityLimit = properties.capacityLimit,
                    windowMillis = properties.window.toMillis(),
                )
            } catch (e: Exception) {
                // fail-open — Redis 장애로 등록 기능 전체가 멈추는 것보다, 한도가 잠시 안 걸리는 쪽이 낫다.
                // 이 선택의 위험(장애 창 동안 한도 없이 호출됨)은 제한적이다: Redis 가 죽으면 refresh 토큰 저장소도
                // 함께 죽어 로그인 흐름이 이미 망가지므로, 그 창에서 대량 호출이 지속되기 어렵다.
                // 외부 의존성 실패라 warn (로그 레벨 정책).
                //
                // runCatching 이 아니라 catch(Exception) 인 이유: runCatching 은 Throwable 을 잡아 OutOfMemoryError
                // 같은 치명적 Error 까지 삼킨다. 그런 상황에서 fail-open 으로 요청을 계속 받으면 장애를 키운다.
                log.warn("아이템 한도 검사 실패 — 통과시킨다(fail-open). scope={} ownerId={} amount={}", scope, ownerId, amount, e)
                return
            }

        when (verdict) {
            is ItemQuotaVerdict.Allowed -> warnIfCapacityAlertCrossed(verdict.capacityUsed, amount)
            // 429 는 클라이언트 계약 위반이라 GlobalExceptionHandler 가 info 로 남긴다 — 여기서 또 찍지 않는다.
            is ItemQuotaVerdict.OwnerExceeded -> throw ItemQuotaException.exceeded(errorCode, verdict.retryAfterSeconds)
            // 503 도 핸들러가 warn 으로 남긴다. 한 번 차면 창이 끝날 때까지 모든 요청이 여기로 오므로,
            // 거부 건마다 여기서 또 찍으면 로그가 배로 늘기만 한다. 도달 사실은 아래 경고선 로그가 이미 알렸다.
            is ItemQuotaVerdict.CapacityExceeded -> throw ItemQuotaException.capacityExceeded(verdict.retryAfterSeconds)
        }
    }

    // 전역 가용량이 경고선을 넘긴 순간 한 줄 남긴다. 상한에 닿으면 이미 사용자가 막히고 있어 늦으므로,
    // 이 로그가 실질 방어선이다 — 알림 룰이 이 문구를 집어 Discord 로 보낸다.
    //
    // 대응은 "상한을 올린다" 가 기본이 아니다. 정상 성장인지, 특정 사용자·IP 의 이상 패턴인지, 파싱 실패로 인한
    // 재시도 폭증인지를 먼저 가르고, 정상 성장으로 확인된 뒤에만 올린다.
    private fun warnIfCapacityAlertCrossed(
        capacityUsed: Long,
        amount: Int,
    ) {
        if (!properties.crossedCapacityAlert(capacityUsed, amount)) return
        log.warn(
            "아이템 등록 전역 가용량 경고선 도달 — used={} threshold={} limit={} window={}",
            capacityUsed,
            properties.capacityAlertThreshold,
            properties.capacityLimit,
            properties.window,
        )
    }
}
