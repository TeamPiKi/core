package com.depromeet.piki.tournament.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

// 어떤 유저가 어떤 토너먼트에 참여했는지를 명시 관리하는 매핑 테이블. 한 사람의 "이 방에서의 참여" 하나가 한 행이다.
// userId 는 게스트·회원 모두 수용 (현재는 Guest 의 UUID).
//
// 토너먼트 도메인은 User 를 끌어오지 않고, 사람을 가리킬 때 userId 가 아니라 이 행의 id 를 쓴다
// (tournaments.owner_tournament_user_id · tournament_histories.tournament_user_id). User 와의 접점은 이 행의 userId 하나다.
// userId 로 바꾸지 않는 이유(#1188):
//   - userId 는 바뀐다. 게스트가 기존 회원 계정으로 로그인하면 userId 가 회원 것으로 교체된다(#1081 GuestPlayTakeover).
//     행 id 를 가리키면 이 행의 userId 하나만 고치면 되고, 방장·히스토리는 행 id 를 따라 그대로 넘어간다.
//   - 승계 중 같은 방에 게스트·회원 행이 둘 다 있으면 게스트 행을 접는다. 히스토리가 행 id 에 묶여 있어 두 사람의
//     선택 기록이 한 키로 섞이지 않는다.
//   - 대진표 seed 가 seedOf(tournamentUserId, round)다(RoundBracket). 승계로 userId 가 바뀌어도 진행 중 대진표가 그대로다.
//   - 표시명은 nickname 스냅샷(#1018)이 여기 있어, 화면을 그릴 때 User 를 다시 읽지 않아도 된다.
// 예외로 tournament_items.user_id 는 "아이템을 담은 사람" 이라 userId 를 쓰고, 승계 때 따로 옮긴다.
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

    // 플레이 시작 — 명단(PENDING)에서 진행으로. 멤버·게스트가 자기 판을 시작하는 순간.
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
