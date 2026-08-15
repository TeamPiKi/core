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
    private val settings: ItemQuotaSettings,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 두 축을 함께 확인하고, 통과하면 그만큼 차감한 뒤 반환한다.
    //   - 요청자 몫 소진 → ItemQuotaException(429). errorCode 는 호출 도메인이 넘긴다 — 카운터는 하나지만 사용자에게
    //     보일 문구와 code 는 경로마다 달라야 한다(게스트가 남의 토너먼트에서 막힌 응답에 오너의 사용량이 드러나면 안 된다).
    //   - 전역 가용량 소진 → ItemQuotaException(503). 어느 도메인에서 닿든 원인이 같아 공통 code 를 쓴다.
    //
    // ownerId 는 요청자가 아니라 **몫의 주인**이다. 토너먼트 경로에서는 참여 게스트가 넣어도 오너의 몫에서 깎인다 —
    // 게스트 계정은 무한 발급되므로 요청자 기준으로 세면 계정을 갈아타며 한도를 리셋할 수 있고, 오너는 반드시
    // 회원이라(토너먼트 생성이 회원 전용) 소셜 계정 생성 비용이 그 우회를 막는다.
    fun consume(
        ownerId: UUID,
        amount: Int,
        errorCode: ErrorCode,
    ) {
        // 값 한 벌을 한 번만 읽는다 — 판정 도중 백오피스 저장이 끼어들어도 이 요청은 끝까지 같은 값으로 판단한다.
        val quota = settings.current()
        if (!quota.enabled) return

        val verdict =
            try {
                store.tryConsume(
                    ownerKey = RedisItemQuotaStore.USER_KEY_PREFIX + ownerId,
                    capacityKey = RedisItemQuotaStore.CAPACITY_KEY,
                    amount = amount,
                    ownerLimit = quota.userLimit,
                    capacityLimit = quota.capacityLimit,
                    windowMillis = quota.window.toMillis(),
                )
            } catch (e: Exception) {
                // fail-open — Redis 장애로 등록 기능 전체가 멈추는 것보다, 한도가 잠시 안 걸리는 쪽이 낫다.
                // 이 선택의 위험(장애 창 동안 한도 없이 호출됨)은 제한적이다: Redis 가 죽으면 refresh 토큰 저장소도
                // 함께 죽어 로그인 흐름이 이미 망가지므로, 그 창에서 대량 호출이 지속되기 어렵다.
                // 외부 의존성 실패라 warn (로그 레벨 정책).
                //
                // runCatching 이 아니라 catch(Exception) 인 이유: runCatching 은 Throwable 을 잡아 OutOfMemoryError
                // 같은 치명적 Error 까지 삼킨다. 그런 상황에서 fail-open 으로 요청을 계속 받으면 장애를 키운다.
                log.warn("아이템 한도 검사 실패 — 통과시킨다(fail-open). ownerId={} amount={}", ownerId, amount, e)
                return
            }

        when (verdict) {
            is ItemQuotaVerdict.Allowed -> warnIfCapacityAlertCrossed(quota, verdict.capacityUsed, amount)
            // 429 는 클라이언트 계약 위반이라 GlobalExceptionHandler 가 info 로 남긴다 — 여기서 또 찍지 않는다.
            is ItemQuotaVerdict.OwnerExceeded -> throw ItemQuotaException.exceeded(errorCode, verdict.retryAfterSeconds)
            // 503 도 핸들러가 warn 으로 남긴다. 한 번 차면 창이 끝날 때까지 모든 요청이 여기로 오므로,
            // 거부 건마다 여기서 또 찍으면 로그가 배로 늘기만 한다. 도달 사실은 아래 경고선 로그가 이미 알렸다.
            is ItemQuotaVerdict.CapacityExceeded -> throw ItemQuotaException.capacityExceeded(verdict.retryAfterSeconds)
        }
    }

    // 전역 가용량이 경고선을 넘긴 순간 한 줄 남긴다. 상한에 닿으면 이미 사용자가 막히고 있어 늦으므로,
    // 이 로그가 실질 방어선이다 — Loki 알림 룰이 이 줄을 집어 Discord 로 보낸다.
    //
    // 대응은 "상한을 올린다" 가 기본이 아니다. 정상 성장인지, 특정 사용자·IP 의 이상 패턴인지, 파싱 실패로 인한
    // 재시도 폭증인지를 먼저 가르고, 정상 성장으로 확인된 뒤에만 올린다.
    private fun warnIfCapacityAlertCrossed(
        quota: ItemQuotaSnapshot,
        capacityUsed: Long,
        amount: Int,
    ) {
        if (!quota.crossedCapacityAlert(capacityUsed, amount)) return
        // 알림이 매칭하는 줄이라 **사람이 읽는 문구가 아니라 기계가 읽는 형식**으로 쓴다(item.parse.result 와 같은 규약):
        // 고정 이벤트 키 + logfmt(`키=값`). 사람이 읽을 설명은 알림 룰의 summary 가 한국어로 담는다.
        //
        // 한국어 산문으로 쓰면 알림이 그 문구를 검색어로 삼게 되는데, 문구를 다듬는 순간 매칭이 깨지고
        // **알림이 조용히 죽는다** — "안 울림" 은 정상 상태와 구분되지 않아 아무도 알아채지 못한다.
        // 값도 logfmt 여야 라벨로 추출돼 Discord 문구에 실린다("뭔가 울렸다" 가 아니라 "사용량 1980 / 한도 3000").
        //
        // window 는 Duration.toString(PT1H)이 아니라 초로 남긴다 — logfmt 값은 숫자여야 알림에서 비교·표시가 쉽다.
        log.warn(
            "{} used={} threshold={} limit={} windowSeconds={}",
            CAPACITY_ALERT_EVENT,
            capacityUsed,
            quota.capacityAlertThreshold,
            quota.capacityLimit,
            quota.window.seconds,
        )
    }

    companion object {
        // Loki 알림 룰의 매칭 앵커. **이 문자열이 곧 알림 계약이다** — 바꾸면 룰도 함께 바꿔야 하고,
        // 안 바꾸면 알림이 조용히 죽는다. 상수로 빼 테스트가 같은 값을 참조하게 해 오타·표류를 막는다.
        const val CAPACITY_ALERT_EVENT = "item.quota.capacity.alert"
    }
}
