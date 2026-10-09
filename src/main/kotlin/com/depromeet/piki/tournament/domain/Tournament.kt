package com.depromeet.piki.tournament.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime
import kotlin.random.Random

@Entity
@Table(name = "tournaments")
class Tournament(

    ownerTournamentUserId: Long,

    @Column(name = "name", nullable = false)
    val name: String,

    @Column(name = "invite_code", nullable = false, length = 6)
    val inviteCode: String,

    inviteExpiresAt: LocalDateTime,

    @Enumerated(value = EnumType.STRING)
    @Column(columnDefinition = "varchar(50)")
    var status: TournamentStatus = TournamentStatus.PENDING,

    @Column(name = "source_tournament_id")
    val sourceTournamentId: Long? = null,
) : LongBaseEntity() {
    @Column(nullable = false)
    var ownerTournamentUserId: Long = ownerTournamentUserId
        protected set

    @Column(nullable = false)
    var inviteExpiresAt: LocalDateTime = inviteExpiresAt
        protected set

    @Column
    var startedAt: LocalDateTime? = null
        protected set

    @Column(name = "play_link_expires_at")
    var playLinkExpiresAt: LocalDateTime? = null

    // active_invite_code 는 초대코드 조회 인덱스(uk_tournaments_active_invite_code)가 걸린
    // generated STORED 컬럼(= IF(deleted_at IS NULL, invite_code, NULL))이다.
    // DB 가 계산하므로 앱에서 쓰지 않고(read-only), 활성 초대코드 조회를 이 컬럼으로 태워
    // 인덱스를 쓰기 위해 매핑만 둔다. base 컬럼 invite_code 로 조회하면 인덱스를 못 써 풀스캔이 된다.
    @Column(name = "active_invite_code", insertable = false, updatable = false)
    val activeInviteCode: String? = null

    fun assignOwner(tournamentUserId: Long) {
        ownerTournamentUserId = tournamentUserId
    }

    // 출전 값은 이 시각까지의 버전으로 계산한다. 호출부가 출전 아이템 행을 잠근 뒤 불러야 버전 생성과 순서가 맞는다
    fun start() {
        check(isPending()) { "start는 PENDING 상태에서만 호출 가능" }
        this.status = TournamentStatus.IN_PROGRESS
        this.startedAt = LocalDateTime.now()
    }

    fun complete() {
        check(isInProgress()) { "complete는 IN_PROGRESS 상태에서만 호출 가능" }
        this.status = TournamentStatus.COMPLETED
    }

    // #1027: 토너먼트 status 는 정의(구성) 상태만 담고 완료는 참여 행이 갖는다. "주최자가 자기 플레이를 완료했나"
    // 는 서비스가 owner 참여 행(status)으로 판정하고, 도메인은 "구성 단계는 지났나(PENDING 아님)" 불변식만 지킨다.
    // 레거시 데이터(status=COMPLETED)도 PENDING 이 아니므로 그대로 통과한다.
    fun createPlayLink(expiresAt: LocalDateTime) {
        check(!isPending()) { "createPlayLink는 시작된 토너먼트에서만 호출 가능" }
        playLinkExpiresAt = expiresAt
    }

    fun expirePlayLink() {
        playLinkExpiresAt = LocalDateTime.now().minusSeconds(1)
    }

    fun isFinalRound(currentRound: Int): Boolean = currentRound == FINAL_ROUND_SIZE

    fun isPending(): Boolean = status == TournamentStatus.PENDING

    fun isInProgress(): Boolean = status == TournamentStatus.IN_PROGRESS

    fun isCompleted(): Boolean = status == TournamentStatus.COMPLETED

    fun isRoot(): Boolean = sourceTournamentId?.let { false } ?: true

    fun updateInviteExpiry(newExpiresAt: LocalDateTime) {
        check(isPending()) { "updateInviteExpiry는 PENDING 상태에서만 호출 가능" }
        inviteExpiresAt = newExpiresAt
    }

    fun isInviteValid(): Boolean = LocalDateTime
        .now()
        .isBefore(inviteExpiresAt)

    fun isPlayLinkValid(): Boolean = playLinkExpiresAt?.let {
        LocalDateTime
            .now()
            .isBefore(it)
    } ?: false

    companion object {
        internal const val FINAL_ROUND_SIZE = 2
        private val LETTERS = ('A'..'Z').toList()
        private val DIGITS = ('0'..'9').toList()

        fun generateInviteCode(): String {
            val letters = (1..3)
                .map { LETTERS[Random.nextInt(LETTERS.size)] }
                .joinToString("")
            val digits = (1..3)
                .map { DIGITS[Random.nextInt(DIGITS.size)] }
                .joinToString("")
            return letters + digits
        }
    }
}
