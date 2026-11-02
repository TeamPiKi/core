package com.depromeet.piki.folder.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(FolderErrorCode 가 single source).
class FolderException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun notFound(): FolderException = FolderException(FolderErrorCode.NOT_FOUND)

        fun invalidName(): FolderException = FolderException(FolderErrorCode.INVALID_NAME)

        fun invalidWishCount(): FolderException = FolderException(FolderErrorCode.INVALID_WISH_COUNT)

        fun sameFolderMove(): FolderException = FolderException(FolderErrorCode.SAME_FOLDER_MOVE)
    }
}
