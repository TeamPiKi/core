package com.depromeet.piki.image.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 이미지 등록 v2(presigned 업로드) confirm 단계의 계약 위반. 클라이언트가 발급 형식이 아닌 key 를 주거나,
// presigned URL 로 S3 에 올리지 않은 채 confirm 을 호출하면 도달한다 — 멀쩡한 클라의 잘못된 순서라 400.
// key 원본은 내부 참조라 message 에 싣지 않고 고정 사용자 대면 문구로 둔다(내부 정보 비노출).
class ImageUploadException private constructor(
    message: String,
    override val category: ErrorCategory,
    override val httpStatus: HttpStatus,
) : BaseException(message),
    HttpMappable {
    companion object {
        fun invalidKey(): ImageUploadException =
            ImageUploadException(
                "올바르지 않은 이미지 업로드 정보예요. 업로드를 다시 시도해 주세요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        fun notUploaded(): ImageUploadException =
            ImageUploadException(
                "아직 업로드되지 않은 이미지예요. 업로드를 마친 뒤 다시 시도해 주세요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )
    }
}
