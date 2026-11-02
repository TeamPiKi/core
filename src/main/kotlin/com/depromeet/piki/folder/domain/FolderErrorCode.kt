package com.depromeet.piki.folder.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// FolderException 의 code 배정표(#1264). 번호는 append-only — 재배치·결번 침범 금지.
// 명세 초안의 "같은 이름 금지" 는 디자인을 따라 빠져, 초안의 003-005 가 002-004 로 당겨진 상태로 처음 배정한다.
enum class FolderErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    // 남의 폴더도 같은 응답으로 숨긴다 — 폴더 id 를 바꿔 가며 있는지 알아낼 수 없게
    NOT_FOUND(
        "FOLDER-001",
        ErrorCategory.NOT_FOUND,
        "폴더를 찾을 수 없어요.",
    ),
    INVALID_NAME(
        "FOLDER-002",
        ErrorCategory.INVALID_INPUT,
        "폴더 이름은 1에서 20자로 입력해 주세요.",
    ),
    INVALID_WISH_COUNT(
        "FOLDER-003",
        ErrorCategory.INVALID_INPUT,
        "한 번에 최대 100개까지 담을 수 있어요.",
    ),
    SAME_FOLDER_MOVE(
        "FOLDER-004",
        ErrorCategory.INVALID_INPUT,
        "다른 폴더를 선택해 주세요.",
    ),
}
