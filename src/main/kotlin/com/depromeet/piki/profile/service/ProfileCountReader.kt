package com.depromeet.piki.profile.service

import com.depromeet.piki.profile.repository.ProfileCountJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 다른 사람 화면에 뜨는 "공개 폴더 N개 · 위시 N개" 를 읽는다. 친구 목록(#1288)과 다른 사람 프로필(#1290)이 쓴다.
//
// 공개 여부 판정은 하지 않는다 — 여기 들어오는 id 는 호출자가 ProfileExposure 로 이미 걸러낸 사람들이다.
// 프로필 비공개인 사람은 수를 세지 않고 "비공개" 로 표시하므로 애초에 이 목록에 오지 않는다.
@Service
class ProfileCountReader(
    private val profileCountJpaRepository: ProfileCountJpaRepository,
) {
    /**
     * [userIds] 각각의 공개 폴더 수와 공개 폴더에 든 위시 수.
     *
     * 빈 입력에 질의하지 않는다 — `IN ()` 은 DB 마다 문법이 갈리고 어차피 답이 빈 맵이다.
     *
     * 센 것이 하나도 없는 사용자는 결과에 행이 없다(GROUP BY). 호출자가 0 을 보도록 여기서 채워 돌려준다 —
     * 맵에서 빠진 id 를 호출자가 매번 orEmpty 로 다루게 하면 "폴더가 0개" 와 "안 물어봤다" 가 섞인다.
     */
    @Transactional(readOnly = true)
    fun countsOf(userIds: Collection<UUID>): Map<UUID, ProfileCounts> {
        if (userIds.isEmpty()) return emptyMap()
        val folders = profileCountJpaRepository.countPublicFolders(userIds).associate { it.userId to it.count }
        val wishes = profileCountJpaRepository.countWishesInPublicFolders(userIds).associate { it.userId to it.count }
        return userIds.associateWith { ProfileCounts(folders[it] ?: 0L, wishes[it] ?: 0L) }
    }
}

// 한 사람의 공개 집계. 둘 다 공개 폴더 기준이다
data class ProfileCounts(
    val folderCount: Long,
    val wishCount: Long,
)
