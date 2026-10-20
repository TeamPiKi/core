package com.depromeet.piki.image.service.remote

import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.contracts.extraction.v1.ImageExtractionRequest
import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.ExtractionEndpoints
import com.depromeet.piki.product.service.remote.ExtractionModelSettings
import com.depromeet.piki.product.service.remote.RemoteExtractionContract
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class HttpImageSnapshotExtractor(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
    private val s3Properties: S3Properties,
    private val modelSettings: ExtractionModelSettings,
) : ImageSnapshotExtractor {
    override fun extract(key: ProductImageKey): ProductSnapshot {
        val request =
            ImageExtractionRequest
                .newBuilder()
                .setBucket(s3Properties.bucket)
                .setKey(key.value)
        modelSettings.modelOverrideOf(ParseInputType.IMAGE)?.let(request::setModel)
        return RemoteExtractionContract.postForSnapshot(
            restClient = restClient,
            path = ExtractionEndpoints.IMAGE_EXTRACTION,
            request = request.build(),
            target = "key=$key",
        )
    }
}
