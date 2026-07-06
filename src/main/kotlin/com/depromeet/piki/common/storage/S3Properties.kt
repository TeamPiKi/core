package com.depromeet.piki.common.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "s3")
data class S3Properties(
    val bucket: String,
    val region: String = "ap-northeast-2",
    // 업로드한 객체의 공개 베이스 URL (예: https://{bucket}.s3.{region}.amazonaws.com). 끝 슬래시는 무시한다.
    val publicBaseUrl: String,
    // 이미지 등록 v2 presigned 업로드 URL 의 유효 기간. 클라이언트가 발급받아 S3 에 직접 PUT 할 수 있는 시간이다.
    // 너무 길면 URL 유출 시 악용 창이 커지고, 너무 짧으면 느린 네트워크에서 업로드가 만료된다 — 5분을 기본으로 둔다.
    val presignedUploadExpiry: Duration = Duration.ofMinutes(5),
) {
    init {
        require(bucket.isNotBlank()) { "s3.bucket 이 비어 있습니다." }
        require(publicBaseUrl.isNotBlank()) { "s3.public-base-url 이 비어 있습니다." }
        require(!presignedUploadExpiry.isZero && !presignedUploadExpiry.isNegative) {
            "s3.presigned-upload-expiry 는 양수여야 합니다."
        }
        // SigV4 presigned URL 의 서명 유효기간은 7일 미만이어야 한다. 서명은 로컬 계산이라 상한을 넘겨도 발급은 통과하고
        // 클라가 실제 PUT 할 때에야 S3 가 거부하므로, 설정 실수를 부팅 시점에 잡도록 상한도 검증한다.
        // presigned 업로드는 어차피 짧게(기본 5분) 쓰므로 경계(7일 정각)를 굳이 허용하지 않고 strict 미만으로 좁힌다.
        require(presignedUploadExpiry < MAX_PRESIGNED_UPLOAD_EXPIRY) {
            "s3.presigned-upload-expiry 는 7일 미만이어야 합니다."
        }
    }

    companion object {
        // SigV4 presigned URL 서명 유효기간의 프로토콜 상한(미만).
        private val MAX_PRESIGNED_UPLOAD_EXPIRY: Duration = Duration.ofDays(7)
    }
}
