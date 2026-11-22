package com.depromeet.piki.block.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

// 차단 한 줄(#1289). 효과는 양방향이지만 행은 한 방향이다 — 누가 차단했는지를 알아야
// "차단한 사용자입니다"(차단한 쪽)와 비공개 프로필(차단당한 쪽)을 가를 수 있다(5단계 화면).
//
// 해제는 행을 지운다(하드). 상속받은 deletedAt 은 이 엔티티에서 늘 null 이다.
@Entity
@Table(name = "user_blocks")
class UserBlock(
    // 차단한 사람
    @Column(name = "user_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,
    // 차단된 사람
    @Column(name = "blocked_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    val blockedId: UUID,
) : LongBaseEntity() {
    init {
        // 자기 자신 차단은 입력 경계(서비스)가 400 으로 거른다. 여기 닿았다면 그 경계가 빠뜨린 것이다
        require(userId != blockedId) { "자기 자신은 차단할 수 없다 — userId=$userId" }
    }
}
