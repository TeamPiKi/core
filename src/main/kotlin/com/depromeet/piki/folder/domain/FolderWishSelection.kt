package com.depromeet.piki.folder.domain

// 빼기 · 옮기기의 대상. 폴더 편집의 "전체 선택" 은 개수 제한 없이 폴더 전부(All), 그 밖은 고른 위시(Ids, 1-100개).
// all 과 wishIds 는 정확히 하나만 — 알림 삭제(all XOR ids)와 같은 계약이다.
sealed interface FolderWishSelection {
    data object All : FolderWishSelection

    data class Ids(
        val ids: FolderWishIds,
    ) : FolderWishSelection

    companion object {
        fun of(
            all: Boolean?,
            wishIds: List<Long>?,
        ): FolderWishSelection {
            if (all != true) return Ids(FolderWishIds.of(wishIds))
            if (!wishIds.isNullOrEmpty()) throw FolderException.invalidSelection()
            return All
        }
    }
}
