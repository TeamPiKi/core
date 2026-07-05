package com.depromeet.piki.product.service.http

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

class PageFetchException private constructor(
    message: String,
    override val category: ErrorCategory,
    override val httpStatus: HttpStatus,
    cause: Throwable? = null,
    // 정적 fetch 가 "차단" 으로 막혔고 실제 브라우저(헤드리스)면 뚫릴 수 있는 신호인지 표시한다. FallbackProductLinkExtractor 가
    // 이 값으로 헤드리스 에스컬레이션 여부를 정한다(에스컬레이션 축은 outbox 재시도 축과 직교). 정책은 "무조건 폴백": 영구 실패는
    // 봇 차단(4xx 클로킹·500/501 봇방어·redirect 이상)일 수 있어 전부 escalatable 로 둔다. 예외 둘만 false — SSRF 로 우리가
    // 막은 내부망(blockedHost, 뚫으면 안 됨)과 일시 오류(RETRYABLE, escalate 가 아니라 재시도 축). 기본 false.
    val escalatable: Boolean = false,
) : BaseException(message, cause),
    HttpMappable {
    companion object {
        // 사용자 입력 URL 의 query/fragment 에 토큰·세션이 박혀 있을 수 있어 어떤 메시지에도
        // link 자체를 노출하지 않는다. 디버깅용 식별자는 호출 지점에서
        // link.safeLogString() (host + path 만 반환) 으로만 warn 로그를 남긴다.

        // 접근 실패는 사용자에겐 같은 안내라 한 상수를 공유한다(어느 단계 실패인지는 호출 지점 로그로 구분).
        private const val LINK_UNREACHABLE = "링크에 접근하지 못했어요. 주소를 다시 확인해 주세요."

        // 대상 페이지 서버가 502/503/504(게이트웨이 오류·과부하·타임아웃) 또는 연결 실패. 일시적일 수 있어
        // 재시도로 복구 가능성이 있다(RETRYABLE).
        fun upstreamError(cause: Throwable): PageFetchException =
            PageFetchException(LINK_UNREACHABLE, ErrorCategory.RETRYABLE, HttpStatus.BAD_GATEWAY, cause)

        // 대상 서버가 500/501 을 준 경우. 일부 쇼핑몰이 봇 차단을 500 으로 응답하고(크림 등), 우리가 fetch 하는 대형 몰은
        // 사실상 상시 가용이라 우리가 받는 500/501 은 대개 진짜 장애가 아니라 봇 방어다. 502/503/504(일시 게이트웨이)와 달리
        // 재시도해도 결정론적으로 재실패하는 영구(SERVER_ERROR → 워커가 즉시 FAILED)로 보고, escalatable=true 로 둔다(헤드리스면
        // 뚫릴 수 있어 Fallback 이 에스컬레이트). body 유무로 봇차단/장애를 나누지 않는다: 봇 방어가 body(캡차·차단 페이지)를
        // 실을 수 있어 구분이 불확실하고, 드문 genuine 500 에 헤드리스 1회 낭비는 감수한다. status 는 외부 의존성 실패라 502.
        fun permanentUpstreamError(cause: Throwable): PageFetchException =
            PageFetchException(LINK_UNREACHABLE, ErrorCategory.SERVER_ERROR, HttpStatus.BAD_GATEWAY, cause, escalatable = true)

        // 4xx (403·404·410·429 등). 입력 URL 문제로 볼 수도 있어 사용자에겐 400 으로 노출하지만, 봇 방어가 404("없는 척")·
        // 403(차단)·429(throttle)로 클로킹할 수 있어 escalatable=true 로 둔다 — 실제 브라우저(헤드리스)면 뚫릴 수 있다.
        // 진짜 삭제된 상품(genuine 404)에도 헤드리스를 한 번 태우는 낭비가 있으나, "폴백했는데도 못 가져온" 비율을 메트릭·로그로
        // 기록해 후속 per-host 튜닝의 근거로 삼는다(무조건 폴백).
        fun clientError(cause: Throwable): PageFetchException =
            PageFetchException(LINK_UNREACHABLE, ErrorCategory.INVALID_INPUT, HttpStatus.BAD_REQUEST, cause, escalatable = true)

        fun emptyBody(): PageFetchException =
            PageFetchException(
                "해당 링크에서 정보를 가져오지 못했어요.",
                ErrorCategory.RETRYABLE,
                HttpStatus.BAD_GATEWAY,
            )

        // redirect 가 hop 상한을 넘어 무한·체인 의심. 대상 페이지의 고정된 비정상 상태라 재시도해도 결정론적으로
        // 재실패하므로 RETRYABLE(재시도 권유)이 아니라 SERVER_ERROR(재시도 불가). status 는 외부 의존성 실패라 502.
        // redirect 기반 차단(챌린지로 튕김)일 수 있고 헤드리스는 redirect 를 네이티브로 다루므로 escalatable=true(무조건 폴백).
        fun tooManyRedirects(): PageFetchException =
            PageFetchException(LINK_UNREACHABLE, ErrorCategory.SERVER_ERROR, HttpStatus.BAD_GATEWAY, escalatable = true)

        // 대상 서버가 3xx 를 주면서 Location 이 없거나 깨진 값을 준 비정상 redirect 응답. 재시도해도 영구 실패라 SERVER_ERROR.
        // redirect 기반 차단일 수 있어 escalatable=true(무조건 폴백) — 헤드리스면 다르게 풀릴 수 있다.
        fun malformedRedirect(cause: Throwable? = null): PageFetchException =
            PageFetchException(LINK_UNREACHABLE, ErrorCategory.SERVER_ERROR, HttpStatus.BAD_GATEWAY, cause, escalatable = true)

        // host 가 사설/메타데이터/loopback 영역으로 resolve 될 때 SSRF 차단 신호. escalatable=false(기본) — 우리가 막은
        // 내부망이라 헤드리스로도 절대 뚫어선 안 된다. "무조건 폴백" 의 유일한 permanent 예외다.
        fun blockedHost(): PageFetchException =
            PageFetchException(
                "등록할 수 없는 링크예요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )
    }
}
