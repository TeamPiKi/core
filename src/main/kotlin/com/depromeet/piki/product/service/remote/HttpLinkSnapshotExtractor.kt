package com.depromeet.piki.product.service.remote

import com.depromeet.piki.contracts.extraction.v1.LinkExtractionRequest
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkException
import com.depromeet.piki.product.routing.DomainAccessPolicy
import com.depromeet.piki.product.service.LinkSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class HttpLinkSnapshotExtractor(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
    private val accessPolicy: DomainAccessPolicy,
    private val modelSettings: ExtractionModelSettings,
) : LinkSnapshotExtractor {
    override fun extract(link: ProductLink): ProductSnapshot {
        // 등록 경계도 막지만 이미 담긴 아이템의 재파싱·새로고침은 여기가 유일한 출구
        if (accessPolicy.blocked(link)) throw ProductLinkException.unsupportedPlatform()
        val request =
            LinkExtractionRequest
                .newBuilder()
                .setUrl(link.value.toString())
                // 허락 원장은 core DB 에만 있고 extractor·renderer 는 무상태라 요청마다 실음
                .setAuthorized(accessPolicy.authorizedFor(link))
        // extractor 박스를 여러 환경이 공유해 저쪽 환경변수로 모델을 잡으면 dev 실험이 prod 를 덮음
        modelSettings.modelOf(ExtractionTarget.LINK)?.let(request::setModel)
        return RemoteExtractionContract.postForSnapshot(
            restClient = restClient,
            path = LINK_EXTRACTION_PATH,
            request = request.build(),
            link = link,
            target = "url=${link.safeLogString()}",
        )
    }

    companion object {
        private const val LINK_EXTRACTION_PATH = "/internal/extractions/link"
    }
}
