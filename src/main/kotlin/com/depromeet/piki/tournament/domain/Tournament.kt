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
    // open class에서 private set이 금지되므로 backing field로 캡슐화한다.
    // Hibernate는 field access로 직접 접근하고, 외부에서는 getter만 노출된다.
    @Column(name = "owner_tournament_user_id", nullable = false)
    private var _ownerTournamentUserId: Long = ownerTournamentUserId

    val ownerTournamentUserId: Long get() = _ownerTournamentUserId

    @Column(name = "invite_expires_at", nullable = false)
    private var _inviteExpiresAt: LocalDateTime = inviteExpiresAt

    val inviteExpiresAt: LocalDateTime get() = _inviteExpiresAt

    @Column(name = "play_link_expires_at")
    var playLinkExpiresAt: LocalDateTime? = null

    // active_invite_code 는 초대코드 조회 인덱스(uk_tournaments_active_invite_code)가 걸린
    // generated STORED 컬럼(= IF(deleted_at IS NULL, invite_code, NULL))이다.
    // DB 가 계산하므로 앱에서 쓰지 않고(read-only), 활성 초대코드 조회를 이 컬럼으로 태워
    // 인덱스를 쓰기 위해 매핑만 둔다. base 컬럼 invite_code 로 조회하면 인덱스를 못 써 풀스캔이 된다.
    @Column(name = "active_invite_code", insertable = false, updatable = false)
    val activeInviteCode: String? = null

    fun assignOwner(tournamentUserId: Long) {
        _ownerTournamentUserId = tournamentUserId
    }

    fun start() {
        check(isPending()) { "start는 PENDING 상태에서만 호출 가능" }
        this.status = TournamentStatus.IN_PROGRESS
    }

    fun complete() {
        check(isInProgress()) { "complete는 IN_PROGRESS 상태에서만 호출 가능" }
        this.status = TournamentStatus.COMPLETED
    }

    fun createPlayLink(expiresAt: LocalDateTime) {
        check(isCompleted()) { "createPlayLink는 COMPLETED 상태에서만 호출 가능" }
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
        _inviteExpiresAt = newExpiresAt
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
