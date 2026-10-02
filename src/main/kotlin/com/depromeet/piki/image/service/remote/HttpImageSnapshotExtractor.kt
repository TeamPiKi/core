package com.depromeet.piki.image.service.remote

import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.ExtractionModelSettings
import com.depromeet.piki.product.service.remote.ExtractionTarget
import com.depromeet.piki.product.service.remote.RemoteExtractionContract
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// 링크와 같은 계약 번역(RemoteExtractionContract)을 쓰고 요청만 다름. URL 대신 원본의 S3 위치(bucket·key)를 넘김
// bucket 을 요청에 싣는 이유: 버킷은 환경(dev/prod)마다 다른데 그 구분은 본 서버 설정(S3Properties)이
// 이미 쥐고 있다 — extractor 는 버킷 무관(무상태)으로 두고 호출자가 자기 버킷을 알려준다.
@Component
class HttpImageSnapshotExtractor(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
    private val s3Properties: S3Properties,
    private val modelSettings: ExtractionModelSettings,
) : ImageSnapshotExtractor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(imageKey: String): ProductSnapshot {
        log.info("image extract route=remote key={}", imageKey)
        // 이미지 추출엔 원본 URL 이 없어 link=null (extractor 계약 §2 image 와 동일).
        return RemoteExtractionContract.postForSnapshot(
            restClient = restClient,
            path = IMAGE_EXTRACTION_PATH,
            request =
                RemoteImageExtractionRequest(
                    bucket = s3Properties.bucket,
                    key = imageKey,
                    model = modelSettings.modelOf(ExtractionTarget.IMAGE),
                ),
            link = null,
            target = "key=$imageKey",
        )
    }

    companion object {
        private const val IMAGE_EXTRACTION_PATH = "/internal/extractions/image"
    }
}

// wire 요청 모델 — 이 클라이언트 밖에서 쓰지 않는다(file-private). 응답은 링크와 공유(RemoteExtractionResponse).
// model 이 null 이면 extractor 가 자기 기본 모델을 쓴다(계약 §2). 링크와 축이 갈려 있어 이미지 경로는
// IMAGE 지정만 따른다 — 이미지는 vision 이라 링크에 맞는 모델이 여기서 맞지 않을 수 있다.
private data class RemoteImageExtractionRequest(
    val bucket: String,
    val key: String,
    val model: String?,
)
