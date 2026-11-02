package com.depromeet.piki.folder.domain

// 한 폴더에 지금 들어 있는 위시. 넣기·빼기·이동의 분기를 여기 모으고, 무엇을 지우고 넣을지만 돌려준다.
// 저장은 호출부가 한다 — DB 를 몰라 분기 전부를 단위 테스트로 망라할 수 있다.
class FolderContents(
    val folderId: Long,
    wishIds: Collection<Long>,
) {
    private val held: Set<Long> = wishIds.toSet()

    // 이미 들어 있는 위시는 건너뛴다
    fun put(ids: Collection<Long>): List<Long> = ids.distinct().filterNot { it in held }

    // 들어 있지 않은 위시는 건너뛴다
    fun takeOut(ids: Collection<Long>): List<Long> = ids.distinct().filter { it in held }

    // 이 폴더에 든 것만 옮긴다. 대상에 이미 있으면 이 폴더에서 빼기만 한다
    fun moveTo(
        target: FolderContents,
        ids: Collection<Long>,
    ): FolderMove {
        if (target.folderId == folderId) throw FolderException.sameFolderMove()
        val moving = takeOut(ids)
        return FolderMove(takeOut = moving, putIntoTarget = target.put(moving))
    }
}

data class FolderMove(
    val takeOut: List<Long>,
    val putIntoTarget: List<Long>,
)
