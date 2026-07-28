package com.depromeet.piki.image.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 이미지 등록 v2(presigned 업로드) confirm 단계의 계약 위반. 클라이언트가 발급 형식이 아닌 key 를 주거나,
// presigned URL 로 S3 에 올리지 않은 채 confirm 을 호출하면 도달한다 — 멀쩡한 클라의 잘못된 순서라 400.
// key 원본은 내부 참조라 message 에 싣지 않고 고정 사용자 대면 문구로 둔다(내부 정보 비노출).
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(ImageUploadErrorCode 가 single source).
class ImageUploadException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun invalidKey(): ImageUploadException = ImageUploadException(ImageUploadErrorCode.INVALID_KEY)

        fun notUploaded(): ImageUploadException = ImageUploadException(ImageUploadErrorCode.NOT_UPLOADED)
    }
}
