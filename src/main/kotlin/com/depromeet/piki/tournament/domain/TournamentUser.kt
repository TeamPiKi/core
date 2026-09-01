package com.depromeet.piki.tournament.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

// 어떤 유저가 어떤 토너먼트에 참여했는지를 명시 관리하는 매핑 테이블.
// userId 는 게스트·회원 모두 수용 (현재는 Guest 의 UUID).
@Entity
@Table(name = "tournament_users")
class TournamentUser(
    @Column(name = "tournament_id", nullable = false)
    val tournamentId: Long,
    @Column(name = "user_id", nullable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,
    // 토너먼트 전용 표시명(#1018). 참여 시점의 프로필 닉네임으로 채워(스냅샷) 이후 프로필 수정에 영향받지 않는다.
    // NULL 은 레거시(마이그레이션 이전 참여) — 표시 시 users.nickname 으로 폴백한다.
    @Column(name = "nickname")
    var nickname: String? = null,
) : LongBaseEntity() {
    // 엔티티 불변식 — 0·음수는 존재할 수 없는 참조다. 정상 흐름에선 닿지 않고, 닿으면 코드 버그.
    init {
        require(tournamentId > 0) { "tournamentId 는 양수여야 한다: $tournamentId" }
    }

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null

    // 토너먼트 닉네임 변경(대기실/입장 화면에서 수정). 프로필(users.nickname)은 건드리지 않는다.
    fun rename(newNickname: String) {
        nickname = newNickname
    }

    fun complete() {
        completedAt = completedAt ?: LocalDateTime.now()
    }

    fun isCompleted() = completedAt?.let { true } ?: false

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }
}
