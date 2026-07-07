package com.depromeet.piki.common.storage

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 이미지 저장(S3 등 외부 스토리지) 실패를 나타내는 계약 예외.
// 멀쩡한 클라이언트의 정상 요청이라도 우리 밖 스토리지 장애로 떨어질 수 있어 도달 가능한 계약 응답이며,
// GeminiApiException(502) 과 같은 결로 502 BAD_GATEWAY 로 매핑한다 — 클라이언트는 재시도로 처리한다.
// message 는 고정 사용자 대면 문구로 두고, 원인은 cause 체인·로그로 남긴다(내부 정보 비노출).
class ImageStorageException private constructor(
    message: String,
    override val category: ErrorCategory,
    cause: Throwable? = null,
) : BaseException(message, cause),
    HttpMappable {
    override val httpStatus: HttpStatus = HttpStatus.BAD_GATEWAY

    companion object {
        fun uploadFailed(cause: Throwable? = null): ImageStorageException =
            ImageStorageException("이미지를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.", ErrorCategory.RETRYABLE, cause)

        fun deleteFailed(cause: Throwable? = null): ImageStorageException =
            ImageStorageException("이미지를 삭제하지 못했어요. 잠시 후 다시 시도해 주세요.", ErrorCategory.RETRYABLE, cause)

        fun downloadFailed(cause: Throwable? = null): ImageStorageException =
            ImageStorageException("이미지를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", ErrorCategory.RETRYABLE, cause)

        fun presignFailed(cause: Throwable? = null): ImageStorageException =
            ImageStorageException("이미지 업로드 URL 을 발급하지 못했어요. 잠시 후 다시 시도해 주세요.", ErrorCategory.RETRYABLE, cause)

        fun existsCheckFailed(cause: Throwable? = null): ImageStorageException =
            ImageStorageException("이미지 업로드 상태를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.", ErrorCategory.RETRYABLE, cause)
    }
}
