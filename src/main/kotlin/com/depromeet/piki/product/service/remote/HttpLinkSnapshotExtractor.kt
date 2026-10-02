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
        if (accessPolicy.blocked(link)) throw ProductLinkException.unsupportedPlatform()
        val request =
            LinkExtractionRequest
                .newBuilder()
                .setUrl(link.value.toString())
                .setAuthorized(accessPolicy.authorizedFor(link))
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
