package com.depromeet.piki.tournament.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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

    // 참여자별 플레이(진행) 상태(#1027). tournaments.status 가 겸직하던 "한 사람의 진행"을 여기로 내렸다.
    // PENDING(명단만, 아직 안 함) → IN_PROGRESS(플레이 시작) → COMPLETED(완주). CLONE 을 없앤 뒤로
    // 한 사람의 진행은 이 컬럼 하나가 온전히 표현한다. tournaments.status 는 정의(구성) 상태만 담는다.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    var status: TournamentStatus = TournamentStatus.PENDING

    // 토너먼트 닉네임 변경(대기실/입장 화면에서 수정). 프로필(users.nickname)은 건드리지 않는다.
    fun rename(newNickname: String) {
        nickname = newNickname
    }

    // 플레이 시작 — 명단(PENDING)에서 진행으로. 멤버·게스트가 자기 판을 시작하는 순간(과거의 CLONE 생성 지점).
    fun startPlaying() {
        check(status == TournamentStatus.PENDING) { "startPlaying 은 PENDING 에서만 호출 가능: $status" }
        status = TournamentStatus.IN_PROGRESS
    }

    fun complete() {
        completedAt = completedAt ?: LocalDateTime.now()
        status = TournamentStatus.COMPLETED
    }

    fun isPlaying() = status == TournamentStatus.IN_PROGRESS

    // 완료 판정의 단일 출처는 status 다(#1027) — completedAt 은 완료 "시각" 기록 전용. 두 필드를 각각 읽으면
    // 백필 누락·부분 갱신 시 같은 행이 완료/대기로 갈리는 split-brain 이 생겨, complete() 가 항상 둘을 함께 세팅하고
    // 판정은 status 하나로 통일한다.
    fun isCompleted() = status == TournamentStatus.COMPLETED

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }
}
