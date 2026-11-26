package com.depromeet.piki.profile.repository

import com.depromeet.piki.folder.domain.Folder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

// 다른 사람 화면에 뜨는 "공개 폴더 N개 · 위시 N개" 집계. 친구 목록(#1288)과 다른 사람 프로필(#1290)이 같은 값을 쓴다.
//
// 여러 사용자를 한 번에 센다 — 친구 목록이 행마다 질의하면 N+1 이 된다. 결과에 없는 사용자는 0 이다
// (GROUP BY 는 행이 없는 사용자를 아예 돌려주지 않는다).
//
// 둘을 한 질의로 합치지 않는다. 폴더 수와 위시 수를 같은 GROUP BY 에 두면 조인이 서로를 곱해
// 폴더마다 위시 수가, 위시마다 폴더 수가 부풀어 오른다. 질의 두 번이 그 함정보다 싸다.
//
// FK·연관관계를 두지 않는 규약이라 암시적 조인으로 엮는다.
interface ProfileCountJpaRepository : JpaRepository<Folder, Long> {
    // 공개 폴더 수. 비공개·삭제 폴더는 빠진다
    @Query(
        "SELECT f.userId AS userId, COUNT(f) AS count FROM Folder f " +
            "WHERE f.userId IN :userIds AND f.isPublic = true AND f.deletedAt IS NULL " +
            "GROUP BY f.userId",
    )
    fun countPublicFolders(
        @Param("userIds") userIds: Collection<UUID>,
    ): List<UserCountView>

    // 공개 폴더에 든 위시 수. 같은 위시가 공개 폴더 여럿에 들어 있어도 한 번만 센다(DISTINCT).
    //
    // 생존 판정은 위시의 deletedAt 으로 한다 — 폴더에서 빼면 연결 행 자체가 사라지는 구조라
    // 연결 쪽에 삭제 가드를 걸지 않는다(FolderWishJpaRepository.countLiveByFolderId 와 같은 방식).
    //
    // 폴더에 안 든 위시는 세지 않는다. 프로필에서 위시는 폴더를 거쳐서만 보이기 때문이다(#1286 노출 규칙).
    @Query(
        "SELECT w.userId AS userId, COUNT(DISTINCT w.id) AS count " +
            "FROM Wish w, FolderWish fw, Folder f " +
            "WHERE w.userId IN :userIds AND w.deletedAt IS NULL " +
            "AND fw.wishId = w.id AND f.id = fw.folderId AND f.isPublic = true AND f.deletedAt IS NULL " +
            "GROUP BY w.userId",
    )
    fun countWishesInPublicFolders(
        @Param("userIds") userIds: Collection<UUID>,
    ): List<UserCountView>
}
