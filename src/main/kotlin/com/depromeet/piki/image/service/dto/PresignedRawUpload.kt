package com.depromeet.piki.image.service.dto

// presigned 발급 결과 한 건 — 클라가 S3 에 직접 PUT 할 URL 과, confirm 때 되돌려줄 raw object key.
// contentType 은 발급 시 서명에 박은 값이라, 클라는 이 content-type 헤더로 PUT 해야 한다(S3 가 강제).
data class PresignedRawUpload(
    val imageKey: String,
    val uploadUrl: String,
    val contentType: String,
)
