package com.depromeet.piki.product.service.remote

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkException
import com.depromeet.piki.product.routing.DomainAccessPolicy
import com.depromeet.piki.product.service.ProductLinkExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// 원격 추출 서비스(extractor)의 링크 추출 클라이언트. 계약 정본은 TeamPiKi/infra 의
// contracts/extraction-api.md 이고, 호출·3갈래 번역(2xx / 422+code / 그 외)·2xx 계약 위반 가드는 이미지 클라이언트
// (HttpImageSnapshotExtractor)와 공유하므로 RemoteExtractionContract 한 곳에 있다 — 여기는 링크 고유의 요청(URL)만 진다.
//
// 차단(BLOCKED)은 여기서 끝난다 — 요청을 아예 내보내지 않으므로 extractor·renderer 는 그 도메인의 존재조차
// 모른다. 등록 경계도 같은 판정을 하지만 그건 사용자에게 즉시 400 을 주기 위한 것이고, 이 검사는 이미 담긴
// 아이템의 재파싱·새로고침까지 덮는 마지막 출구다.
//
// authorized: "이 대상이 플랫폼의 명시적 허락을 받았는가"의 판정. 원장(domain_access_policies)은 이쪽 DB 에만
// 있고 extractor·renderer 는 무상태라, 요청 단위로 실어 보낸다. 기본은 거부라 정책 행이 없는 대부분의 도메인은
// false 로 나간다. 이 값이 여는 것은 추출 모듈이 쓸 수 있는 수단의 범위이고, 그 수단이 무엇인지는 모듈이
// 정한다 — 여기서 알면 저쪽 구현이 바뀔 때 이 서술만 조용히 낡는다. 기본 수단으로 가는 데는 허락이 필요 없다.
//
// model 도 같은 이유로 요청에 싣는다(#875). extractor 박스 하나를 여러 환경이 공유하므로 저쪽 환경변수로
// 모델을 잡으면 dev 실험이 prod 를 덮는다 — 요청 단위로 주면 환경마다 다른 이쪽 DB 가 그대로 경계가 된다.
//
// 링크 파싱의 유일한 ProductLinkExtractor 구현이다. 워커(AsyncItemParsingWorker)는 이 경계 뒤의
// 원격 호출을 모른다 — 파싱은 전부 extractor(Java 서비스)가 한다.
@Component
class HttpProductLinkExtractor(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
    private val accessPolicy: DomainAccessPolicy,
    private val modelSettings: ExtractionModelSettings,
) : ProductLinkExtractor {
    override fun extract(link: ProductLink): ProductSnapshot {
        // 차단 도메인은 요청 자체를 내보내지 않는다. 등록 경계(verifyRegistrable)가 새 등록을 이미 막지만,
        // 그것만으로는 차단 지정 이전에 담긴 아이템의 재파싱·새로고침이 그대로 나간다 — 여기가 extractor 로
        // 나가는 유일한 출구라, 이 한 곳을 막으면 어느 경로로 들어오든 두드리지 않는다.
        // "차단당했다고 판단한 곳에 매번 다시 요청하지 않는다"는 계약이라 성능 판단이 아니다.
        if (accessPolicy.blocked(link)) throw ProductLinkException.unsupportedPlatform()
        return RemoteExtractionContract.postForSnapshot(
            restClient = restClient,
            path = LINK_EXTRACTION_PATH,
            request =
                RemoteLinkExtractionRequest(
                    url = link.value.toString(),
                    // 허락 판정의 원장은 core 다. extractor·renderer 는 이 값만큼 수단을 열 뿐,
                    // 무엇이 허락됐는지 스스로 알지 않는다(무상태).
                    authorized = accessPolicy.authorizedFor(link),
                    model = modelSettings.modelOf(ExtractionTarget.LINK),
                ),
            link = link,
            target = "url=${link.safeLogString()}",
        )
    }

    companion object {
        private const val LINK_EXTRACTION_PATH = "/internal/extractions/link"
    }
}

// wire 요청 모델 — 이 클라이언트 밖에서 쓰지 않는다(file-private). 응답은 이미지와 공유(RemoteExtractionResponse).
// model 이 null 이면 extractor 가 자기 기본 모델을 쓴다(계약 §2) — 지정이 없는 상태를 그대로 흘려보낸다.
private data class RemoteLinkExtractionRequest(
    val url: String,
    val authorized: Boolean,
    val model: String?,
)
