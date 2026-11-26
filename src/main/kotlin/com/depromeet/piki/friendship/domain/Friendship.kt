package com.depromeet.piki.friendship.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

// 단방향 친구 관계 한 줄(#1288). 요청·수락이 없어 내가 추가하면 내 목록에만 생기고 상대는 알지 못한다.
// A→B 와 B→A 는 서로 독립된 행이다 — 둘 다 있으면 서로 친구인 상태일 뿐, 하나가 다른 하나를 만들지 않는다.
//
// 친구 삭제는 행을 지운다(하드). "그 관계가 없던 것으로" 가 전부라 남겨 둘 이력 가치가 없고,
// 삭제 표시만 남기면 유니크가 그것까지 세어 재추가가 막힌다. 상속받은 deletedAt 은 이 엔티티에서 늘 null 이다.
@Entity
@Table(name = "friendships")
class Friendship(
    // 추가한 사람. 이 사람의 친구 목록에 friendId 가 뜬다
    @Column(name = "user_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,
    // 추가된 사람
    @Column(name = "friend_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    val friendId: UUID,
) : LongBaseEntity() {
    init {
        // 자기 자신 추가는 입력 경계(서비스)가 400 으로 거른다. 여기 닿았다면 그 경계가 빠뜨린 것이다
        require(userId != friendId) { "자기 자신과는 친구가 될 수 없다 — userId=$userId" }
    }
}
