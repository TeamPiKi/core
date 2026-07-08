package com.depromeet.piki.product.service.remote

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.routing.ExtractionRoute
import com.depromeet.piki.product.routing.ExtractionRoutingPolicy
import com.depromeet.piki.product.service.ProductLinkExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// 원격 추출 서비스(PIKI-Extractor)의 링크 추출 클라이언트. 계약은 extractor repo 의 docs/api-contract.md 가
// single source 이고, 호출·3갈래 번역(2xx / 422+code / 그 외)·2xx 계약 위반 가드는 이미지 클라이언트
// (HttpImageSnapshotExtractor)와 공유하므로 RemoteExtractionContract 한 곳에 있다 — 여기는 링크 고유의 요청(URL)만 진다.
//
// headlessFirst: 라우팅 정책(HEADLESS_FIRST, DB·백오피스)의 판정을 요청 힌트로 싣는다 — 정책의 단일 진실은
// 이쪽(DB) 이고, 무상태인 extractor 는 요청 단위로만 받는다(계약 §2). extractor 의 headless 스위치가 꺼져
// 있으면 저쪽에서 무시되므로 여기서 따로 게이트하지 않는다 — 스위치는 능력을 가진 쪽(extractor) 한 곳에 둔다.
//
// 링크 파싱의 유일한 ProductLinkExtractor 구현이다. 워커(AsyncItemParsingWorker)는 이 경계 뒤의
// 원격 호출을 모른다 — 파싱은 전부 PIKI-Extractor(Java 서비스)가 한다.
@Component
class HttpProductLinkExtractor(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
    private val routingPolicy: ExtractionRoutingPolicy,
) : ProductLinkExtractor {
    override fun extract(link: ProductLink): ProductSnapshot =
        RemoteExtractionContract.postForSnapshot(
            restClient = restClient,
            path = LINK_EXTRACTION_PATH,
            request =
                RemoteLinkExtractionRequest(
                    url = link.value.toString(),
                    headlessFirst = routingPolicy.routeOf(link) == ExtractionRoute.HEADLESS_FIRST,
                ),
            link = link,
            target = "url=${link.safeLogString()}",
        )

    companion object {
        private const val LINK_EXTRACTION_PATH = "/internal/extractions/link"
    }
}

// wire 요청 모델 — 이 클라이언트 밖에서 쓰지 않는다(file-private). 응답은 이미지와 공유(RemoteExtractionResponse).
private data class RemoteLinkExtractionRequest(
    val url: String,
    val headlessFirst: Boolean,
)
