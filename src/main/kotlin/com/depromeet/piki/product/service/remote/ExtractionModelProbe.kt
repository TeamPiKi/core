package com.depromeet.piki.product.service.remote

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

// 모델 유효성 프로브. 백오피스가 모델을 저장하기 전에 extractor 로 실호출해 "이 모델이 이 경로에서 실제로
// 동작하는가"를 확인한다 (계약: extractor repo docs/api-contract.md, POST /internal/models/probe).
//
// 아는 모델 목록을 코드에 박는 대신 런타임 실측으로 거르는 자리다 — allowlist 를 박으면 새 모델이 나올 때마다
// extractor 배포가 필요해져 "배포 없이 바꾼다"는 목적이 절반 무너진다. 경고창만 띄우는 방식은 게이트가 되지
// 못한다(오타를 친 사람은 자기가 맞게 썼다고 믿고 확인을 누른다).
//
// 판정을 extractor 가 실제 generateContent 로 하는 이유도 같은 결이다 — 메타 조회는 모델의 존재만 보지만
// 우리 요청은 responseSchema · thinkingLevel 을 싣고, 그 비호환은 파싱 전건 실패로 이어진다.
//
// 인터페이스로 두는 것은 이것이 외부 호출 경계이기 때문이다 — 통합 테스트가 실제 extractor 없이 저장 게이트의
// 성공·거절 시나리오를 돌릴 수 있어야 한다 (ProductLinkExtractor 와 같은 구조).
interface ExtractionModelProbe {
    // 유효하면 그냥 반환하고, 아니면 화면에 그대로 띄울 사유를 담아 던진다. 백오피스 컨트롤러가
    // IllegalArgumentException 을 화면 에러로 흡수하는 기존 관례(AdminExtractionPolicyController)를 따른다.
    fun verify(
        target: ExtractionTarget,
        model: String,
    )
}

@Component
class HttpExtractionModelProbe(
    // 추출 호출과 같은 클라이언트를 쓴다. read-timeout 이 55s 라 백오피스 저장이 최악의 경우 그만큼 멈추지만,
    // 프로브는 최소 프롬프트 1회라 실측 수 초이고 없는 모델은 즉시 404 로 돌아온다 — 전용 빈을 늘릴 만큼의
    // 이득이 없다. 느린 모델 때문에 화면이 오래 멈추기 시작하면 그때 프로브 전용 타임아웃으로 가른다.
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
) : ExtractionModelProbe {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun verify(
        target: ExtractionTarget,
        model: String,
    ) {
        try {
            restClient
                .post()
                .uri(PROBE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ProbeRequest(model = model, target = target.name))
                .retrieve()
                .toBodilessEntity()
        } catch (e: RestClientResponseException) {
            throw translate(e, target, model)
        } catch (e: RestClientException) {
            // 연결 실패 · read timeout 등 transport 장애. 모델이 틀렸다는 근거가 아니므로 일시 실패로 안내한다.
            log.warn("모델 프로브 transport 실패: target={} model={}", target, model, e)
            throw IllegalArgumentException(MESSAGE_UNAVAILABLE)
        }
    }

    // 계약 3갈래 번역: 422 만 확정 거절, 그 외 status 는 전부 일시. 추출 계약(RemoteExtractionContract)과 같은
    // 모양이되 그쪽은 ProductSnapshot 을 돌려주므로 코드를 공유하지 않는다.
    private fun translate(
        e: RestClientResponseException,
        target: ExtractionTarget,
        model: String,
    ): IllegalArgumentException {
        if (!e.statusCode.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
            // 실제 원격 status 를 남긴다 — 잘못된 base-url 404 나 인증 401 을 "모델 문제"로 오인하지 않게.
            log.warn("모델 프로브 일시 실패 status={} target={} model={}", e.statusCode.value(), target, model)
            return IllegalArgumentException(MESSAGE_UNAVAILABLE)
        }
        // 실패 응답 모양(code 하나)은 추출 계약과 같으므로 그 DTO 를 그대로 쓴다.
        val code = runCatching { e.getResponseBodyAs(RemoteExtractionFailureResponse::class.java)?.code }.getOrNull()
        // 거절은 계약상 정상 결과(운영자가 없는 모델을 입력)라 info.
        log.info("모델 프로브 거절 code={} target={} model={}", code, target, model)
        return IllegalArgumentException(
            when (code) {
                CODE_MODEL_NOT_FOUND -> MESSAGE_NOT_FOUND
                CODE_MODEL_INCOMPATIBLE -> MESSAGE_INCOMPATIBLE
                // 모르는 code — 이 바이너리보다 새 extractor 가 사유를 늘렸을 수 있다. 거절 사실만 전한다.
                else -> MESSAGE_REJECTED
            },
        )
    }

    // internal 인 이유: 이 사유 문구가 곧 백오피스 화면에 뜨는 계약이라 테스트가 같은 상수로 단언한다.
    // 테스트에 문자열을 다시 적으면 한쪽만 바뀌었을 때 어긋난 채로 통과한다.
    companion object {
        internal const val PROBE_PATH = "/internal/models/probe"

        internal const val CODE_MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
        internal const val CODE_MODEL_INCOMPATIBLE = "MODEL_INCOMPATIBLE"

        internal const val MESSAGE_NOT_FOUND = "응답하지 않는 모델입니다. 모델명을 확인해 주세요."
        internal const val MESSAGE_INCOMPATIBLE = "모델은 있으나 이 경로의 요청을 처리하지 못합니다. 다른 모델을 골라 주세요."
        internal const val MESSAGE_REJECTED = "이 모델은 사용할 수 없습니다."
        internal const val MESSAGE_UNAVAILABLE = "지금은 모델을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
    }
}

// wire 모델 — 이 클래스 밖에서 쓰지 않는다(file-private). 실패 응답은 추출 계약과 모양이 같아
// RemoteExtractionFailureResponse 를 공유한다.
private data class ProbeRequest(
    val model: String,
    val target: String,
)
