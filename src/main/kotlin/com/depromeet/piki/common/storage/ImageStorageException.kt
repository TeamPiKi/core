package com.depromeet.piki.common.storage

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 이미지 저장(S3 등 외부 스토리지) 실패를 나타내는 계약 예외.
// 멀쩡한 클라이언트의 정상 요청이라도 우리 밖 스토리지 장애로 떨어질 수 있어 도달 가능한 계약 응답이며,
// 외부 의존성 실패답게 502 BAD_GATEWAY 로 매핑한다 — 클라이언트는 재시도로 처리한다.
// (종전엔 httpStatus 를 이 클래스가 직접 고정했으나, 이제 RETRYABLE category 가 502 를 소유해 같은 값이 파생된다.)
// message 는 고정 사용자 대면 문구로 두고, 원인은 cause 체인·로그로 남긴다(내부 정보 비노출).
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(ImageStorageErrorCode 가 single source).
class ImageStorageException private constructor(
    override val errorCode: ErrorCode,
    cause: Throwable? = null,
) : BaseException(errorCode.message, cause),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun uploadFailed(cause: Throwable? = null): ImageStorageException =
            ImageStorageException(ImageStorageErrorCode.UPLOAD_FAILED, cause)

        // 호출부가 전부 runCatching 으로 삼켜 응답에는 실리지 않는다(ImageStorageErrorCode.DELETE_FAILED 주석 참고).
        fun deleteFailed(cause: Throwable? = null): ImageStorageException =
            ImageStorageException(ImageStorageErrorCode.DELETE_FAILED, cause)

        fun presignFailed(cause: Throwable? = null): ImageStorageException =
            ImageStorageException(ImageStorageErrorCode.PRESIGN_FAILED, cause)

        fun existsCheckFailed(cause: Throwable? = null): ImageStorageException =
            ImageStorageException(ImageStorageErrorCode.EXISTS_CHECK_FAILED, cause)
    }
}
