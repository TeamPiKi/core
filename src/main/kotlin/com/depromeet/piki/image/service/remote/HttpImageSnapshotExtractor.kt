package com.depromeet.piki.image.service.remote

import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.RemoteExtractionContract
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// 이미지 파싱 전체(download→OCR→crop→결과 업로드)를 원격 추출 서비스(PIKI-Extractor)에 위임하는 클라이언트.
// 호출·계약 번역은 링크와 같은 3갈래(RemoteExtractionContract)를 공유하고 요청만 다르다 — URL 대신 raw 가 적재된
// S3 위치(bucket·key)를 넘기면 extractor 가 download→OCR→crop→업로드까지 끝내고 업로드된 결과 imageUrl 을 돌려준다.
// bucket 을 요청에 싣는 이유: 버킷은 환경(dev/staging/prod)마다 다른데 그 구분은 본 서버 설정(S3Properties)이
// 이미 쥐고 있다 — extractor 는 버킷 무관(무상태)으로 두고 호출자가 자기 버킷을 알려준다.
//
// 링크와 달리 host 차원이 없어 게이트는 이진이다 — 별도 라우팅 빈 없이 이 빈 자체가 @Primary 로
// embedded(EmbeddedImageSnapshotExtractor)를 가로챈다. 두 키가 모두 true 여야 뜬다:
//   enabled       — 원격 서브시스템 마스터 스위치(RestClient 빈이 이 키로 뜬다). false 한 줄이 링크·이미지 전체 롤백.
//   image-enabled — 이미지만의 독립 롤아웃 게이트. 링크(hosts)와 따로 켜고 끈다.
// 기본(image-enabled=false)이면 이 빈이 없어 embedded 가 유일 후보 — 현행과 완전 동일(zero-diff)이고 통합 테스트의
// 스텁 @Primary 구조와도 충돌하지 않는다(#680 의 RoutingProductLinkExtractor 와 같은 결). 두 키 모두
// properties 필드 없이 @ConditionalOnProperty 만 읽는다(독자 단일화 — relaxed 바인딩 분열 차단).
// 주의: image-enabled=true 인데 enabled=false 면 이 빈이 안 떠 조용히 embedded 로 남는다(부팅 오류 없음) —
// deploy.yml 이 그 반쪽 켜짐을 배포 로그 warning 으로 드러낸다.
@Primary
@Component
@ConditionalOnProperty(prefix = "product.extract.remote", name = ["enabled", "image-enabled"], havingValue = "true")
class HttpImageSnapshotExtractor(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
    private val s3Properties: S3Properties,
) : ImageSnapshotExtractor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(imageKey: String): ProductSnapshot {
        // 전환기 롤아웃 관측용 — 어떤 이미지가 원격을 탔는지의 원장(링크의 route=remote 로그와 같은 결).
        // key 는 내부 식별자(items/raw/{uuid})라 로그에 안전하다.
        log.info("image extract route=remote key={}", imageKey)
        // 이미지 추출엔 원본 URL 이 없어 link=null — embedded 경로(GeminiImageResult.toProductSnapshot)와 같은 계약.
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
