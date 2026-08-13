package com.depromeet.piki.common.ratelimit

import com.depromeet.piki.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

// 아이템 등록 경로가 부르는 한도 게이트(#339).
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

    // 한도를 넘으면 ItemQuotaException(429)을 던지고, 통과하면 그만큼 차감한 뒤 반환한다.
    // errorCode 는 호출 도메인이 넘긴다 — 사용자에게 보일 문구와 code 의 소유권은 도메인에 있다.
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
                    key = scope.keyPrefix + ownerId,
                    amount = amount,
                    limit = properties.limitOf(scope),
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
            is ItemQuotaVerdict.Allowed -> return
            // 429 는 클라이언트 계약 위반이라 GlobalExceptionHandler 가 info 로 남긴다 — 여기서 또 찍지 않는다.
            is ItemQuotaVerdict.Exceeded -> throw ItemQuotaException.exceeded(errorCode, verdict.retryAfterSeconds)
        }
    }
}
