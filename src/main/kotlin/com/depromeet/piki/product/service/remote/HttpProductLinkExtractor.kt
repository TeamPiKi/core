package com.depromeet.piki.product.service.remote

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// 원격 추출 서비스(PIKI-Extractor)의 링크 추출 클라이언트. 계약은 extractor repo 의 docs/api-contract.md 가
// single source 이고, 호출·3갈래 번역(2xx / 422+code / 그 외)·2xx 계약 위반 가드는 이미지 클라이언트
// (HttpImageSnapshotExtractor)와 공유하므로 RemoteExtractionContract 한 곳에 있다 — 여기는 링크 고유의 요청(URL)만 진다.
//
// ProductLinkExtractor 를 구현하지 않는다 — 진입점(ProductLinkExtractor) 주입 후보가 둘(Fallback + 이 빈)이 되는
// 모호성을 피한다. 호출자는 RoutingProductLinkExtractor 뿐이며, 원격 서브시스템과 함께 같은 키로 뜨고 꺼진다.
@Component
@ConditionalOnProperty(prefix = "product.extract.remote", name = ["enabled"], havingValue = "true")
class HttpProductLinkExtractor(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
) {
    fun extract(link: ProductLink): ProductSnapshot =
        RemoteExtractionContract.postForSnapshot(
            restClient = restClient,
            path = LINK_EXTRACTION_PATH,
            request = RemoteLinkExtractionRequest(link.value.toString()),
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
)
