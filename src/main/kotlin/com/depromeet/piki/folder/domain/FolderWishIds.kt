package com.depromeet.piki.folder.domain

// 폴더에 넣고 빼고 옮길 위시 id 목록. 중복은 같은 위시를 가리킬 뿐이라 distinct 로 정규화하고,
// 비어 있거나 상한을 넘으면 400 으로 거부한다. WishDeleteIds 와 같은 상한이다.
@JvmInline
value class FolderWishIds private constructor(
    val values: List<Long>,
) {
    companion object {
        const val MAX_COUNT = 100

        fun of(rawIds: List<Long>?): FolderWishIds {
            val distinct = rawIds.orEmpty().distinct()
            if (distinct.isEmpty() || distinct.size > MAX_COUNT) throw FolderException.invalidWishCount()
            return FolderWishIds(distinct)
        }
    }
}
