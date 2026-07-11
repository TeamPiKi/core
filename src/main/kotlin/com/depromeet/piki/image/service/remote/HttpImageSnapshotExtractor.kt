package com.depromeet.piki.image.service.remote

import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.RemoteExtractionContract
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// 이미지 파싱 전체(download→OCR→crop→결과 업로드)를 원격 추출 서비스(extractor)에 위임하는 클라이언트.
// 호출·계약 번역은 링크와 같은 3갈래(RemoteExtractionContract)를 공유하고 요청만 다르다 — URL 대신 raw 가 적재된
// S3 위치(bucket·key)를 넘기면 extractor 가 download→OCR→crop→업로드까지 끝내고 업로드된 결과 imageUrl 을 돌려준다.
// bucket 을 요청에 싣는 이유: 버킷은 환경(dev/staging/prod)마다 다른데 그 구분은 본 서버 설정(S3Properties)이
// 이미 쥐고 있다 — extractor 는 버킷 무관(무상태)으로 두고 호출자가 자기 버킷을 알려준다.
//
// 이미지 파싱의 유일한 ImageSnapshotExtractor 구현이다.
@Component
class HttpImageSnapshotExtractor(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
    private val s3Properties: S3Properties,
) : ImageSnapshotExtractor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(imageKey: String): ProductSnapshot {
        // 이미지 파싱 발주 원장. key 는 내부 식별자(items/raw/{uuid})라 로그에 안전하다.
        // route=remote 토큰은 기존 로그 쿼리 호환용이다.
        log.info("image extract route=remote key={}", imageKey)
        // 이미지 추출엔 원본 URL 이 없어 link=null (extractor 계약 §2 image 와 동일).
        return RemoteExtractionContract.postForSnapshot(
            restClient = restClient,
            path = IMAGE_EXTRACTION_PATH,
            request = RemoteImageExtractionRequest(s3Properties.bucket, imageKey),
            link = null,
            target = "key=$imageKey",
        )
    }

    companion object {
        private const val IMAGE_EXTRACTION_PATH = "/internal/extractions/image"
    }
}

// wire 요청 모델 — 이 클라이언트 밖에서 쓰지 않는다(file-private). 응답은 링크와 공유(RemoteExtractionResponse).
private data class RemoteImageExtractionRequest(
    val bucket: String,
    val key: String,
)
