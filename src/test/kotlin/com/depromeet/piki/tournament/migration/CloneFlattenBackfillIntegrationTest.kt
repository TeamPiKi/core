package com.depromeet.piki.tournament.migration

import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentHistory
import com.depromeet.piki.tournament.domain.TournamentStatus
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.repository.TournamentHistoryJpaRepository
import com.depromeet.piki.tournament.repository.TournamentJpaRepository
import com.depromeet.piki.tournament.repository.TournamentUserJpaRepository
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

// #1027 Phase 2 백필 검증. status 컬럼은 아직 엔티티에 없으므로(Phase 3 매핑) JdbcTemplate 로 시딩·단언한다.
// 클론 형태 데이터를 시딩 → 트랜잭션 커넥션에서 백필 실행 → 결과 단언(@Transactional 롤백으로 격리).
@Transactional
class CloneFlattenBackfillIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var tournamentJpaRepository: TournamentJpaRepository

    @Autowired private lateinit var tournamentUserJpaRepository: TournamentUserJpaRepository

    @Autowired private lateinit var tournamentHistoryJpaRepository: TournamentHistoryJpaRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `백필은 초대 멤버의 클론 플레이를 그 사람의 ROOT 참여 행으로 병합한다`() {
        val ownerId = UUID.randomUUID()
        val (rootId, _) = seedRoot(TournamentStatus.IN_PROGRESS, ownerId)
        val memberId = UUID.randomUUID()
        // 멤버가 ROOT 에 참여(roster) — 플레이 없음(status PENDING, completed_at NULL).
        val memberRootTuId = seedTournamentUser(rootId, memberId).getId()
        // 멤버가 시작 → 자기 클론(COMPLETED) + 클론 TU(완료) + 이력.
        val (cloneId, cloneTuId) = seedClone(rootId, memberId, TournamentStatus.COMPLETED, completed = true)
        val historyId = seedHistory(cloneId, cloneTuId).getId()

        runBackfill()

        // 멤버 ROOT 행이 클론 플레이를 흡수: status COMPLETED, completed_at 세팅, 삭제 안 됨.
        assertEquals("COMPLETED", statusOf(memberRootTuId))
        assertTrue(completedAtSet(memberRootTuId))
        assertFalse(deleted(memberRootTuId))
        // 클론 TU 는 soft-delete.
        assertTrue(deleted(cloneTuId))
        // 이력은 (root_id, member_root_tu) 로 재지향.
        assertEquals(rootId, historyTournamentId(historyId))
        assertEquals(memberRootTuId, historyTournamentUserId(historyId))
    }

    @Test
    fun `백필은 링크 게스트의 클론을 ROOT 로 재지향한다(행 하나뿐이라 병합 아님)`() {
        val ownerId = UUID.randomUUID()
        val (rootId, _) = seedRoot(TournamentStatus.COMPLETED, ownerId)
        val guestId = UUID.randomUUID()
        // 링크 게스트: ROOT 참여 행 없이 자기 클론만.
        val (cloneId, cloneTuId) = seedClone(rootId, guestId, TournamentStatus.COMPLETED, completed = true)
        val historyId = seedHistory(cloneId, cloneTuId).getId()

        runBackfill()

        // 클론 TU 자체가 ROOT 참여 행이 된다: tournament_id → ROOT, status COMPLETED, 삭제 안 됨.
        assertEquals(rootId, tournamentIdOf(cloneTuId))
        assertEquals("COMPLETED", statusOf(cloneTuId))
        assertFalse(deleted(cloneTuId))
        // 이력의 tournament_id 도 ROOT 로(tournament_user_id 는 그대로 그 행).
        assertEquals(rootId, historyTournamentId(historyId))
        assertEquals(cloneTuId, historyTournamentUserId(historyId))
    }

    @Test
    fun `백필은 주최자의 self-clone 을 스킵하고 ROOT 플레이를 보존한다`() {
        val ownerId = UUID.randomUUID()
        val (rootId, ownerRootTuId) = seedRoot(TournamentStatus.COMPLETED, ownerId)
        // 주최자가 ROOT 를 직접 완주: ROOT TU 완료 + ROOT 이력.
        completeTournamentUser(ownerRootTuId)
        val rootHistoryId = seedHistory(rootId, ownerRootTuId).getId()
        // 주최자가 자기 링크로 만든 self-clone(같은 유저) + 클론 이력.
        val (cloneId, selfCloneTuId) = seedClone(rootId, ownerId, TournamentStatus.COMPLETED, completed = true)
        val cloneHistoryId = seedHistory(cloneId, selfCloneTuId).getId()

        runBackfill()

        // ROOT 플레이가 정본: 주최자 ROOT TU·이력 보존.
        assertFalse(deleted(ownerRootTuId))
        assertTrue(completedAtSet(ownerRootTuId))
        assertEquals(rootId, historyTournamentId(rootHistoryId))
        assertEquals(ownerRootTuId, historyTournamentUserId(rootHistoryId))
        assertEquals("COMPLETED", statusOf(ownerRootTuId)) // ROOT status 승계(Step A)
        // 중복 self-clone TU·이력은 soft-delete.
        assertTrue(deleted(selfCloneTuId))
        assertTrue(historyDeleted(cloneHistoryId))
    }

    @Test
    fun `백필은 클론당 참가자가 1 이 아니면 중단한다(방어 가드)`() {
        val ownerId = UUID.randomUUID()
        val (rootId, _) = seedRoot(TournamentStatus.IN_PROGRESS, ownerId)
        val guestId = UUID.randomUUID()
        val (cloneId, _) = seedClone(rootId, guestId, TournamentStatus.IN_PROGRESS, completed = false)
        // 예상 못한 데이터: 클론에 참가자 하나 더.
        seedTournamentUser(cloneId, UUID.randomUUID())

        assertFailsWith<IllegalStateException> { runBackfill() }
    }

    // ---- 실행·시딩 헬퍼 ----

    private fun runBackfill() {
        jdbcTemplate.execute(ConnectionCallback { conn -> CloneFlattenBackfill(conn).run() })
    }

    private fun seedRoot(
        status: TournamentStatus,
        ownerId: UUID,
    ): Pair<Long, Long> {
        val root = tournamentJpaRepository.saveAndFlush(newTournament(status = status, sourceTournamentId = null))
        val ownerTu = seedTournamentUser(root.getId(), ownerId)
        root.assignOwner(ownerTu.getId())
        tournamentJpaRepository.saveAndFlush(root)
        return root.getId() to ownerTu.getId()
    }

    // 클론 tournaments 행 + 그 owner 참여 행을 만든다. completed=true 면 클론 TU 에 completed_at 세팅.
    private fun seedClone(
        rootId: Long,
        userId: UUID,
        status: TournamentStatus,
        completed: Boolean,
    ): Pair<Long, Long> {
        val clone = tournamentJpaRepository.saveAndFlush(newTournament(status = status, sourceTournamentId = rootId))
        val cloneTu = seedTournamentUser(clone.getId(), userId)
        if (completed) completeTournamentUser(cloneTu.getId())
        clone.assignOwner(cloneTu.getId())
        tournamentJpaRepository.saveAndFlush(clone)
        return clone.getId() to cloneTu.getId()
    }

    private fun seedTournamentUser(
        tournamentId: Long,
        userId: UUID,
    ): TournamentUser = tournamentUserJpaRepository.saveAndFlush(TournamentUser(tournamentId, userId, null))

    private fun completeTournamentUser(tuId: Long) {
        jdbcTemplate.update("UPDATE tournament_users SET completed_at = NOW(6) WHERE id = ?", tuId)
    }

    private fun seedHistory(
        tournamentId: Long,
        tournamentUserId: Long,
    ): TournamentHistory =
        tournamentHistoryJpaRepository.saveAndFlush(
            TournamentHistory(
                tournamentId = tournamentId,
                tournamentUserId = tournamentUserId,
                currentRound = 2,
                firstTournamentItemId = 1,
                secondTournamentItemId = 2,
                selectedTournamentItemId = 1,
            ),
        )

    private fun newTournament(
        status: TournamentStatus,
        sourceTournamentId: Long?,
    ): Tournament =
        Tournament(
            ownerTournamentUserId = 0L,
            name = "테스트",
            inviteCode = UUID.randomUUID().toString().take(6),
            inviteExpiresAt = LocalDateTime.now().plusDays(1),
            status = status,
            sourceTournamentId = sourceTournamentId,
        )

    // ---- JdbcTemplate 단언 헬퍼 (status 는 미매핑이라 raw 조회) ----

    private fun statusOf(tuId: Long): String =
        jdbcTemplate.queryForObject("SELECT status FROM tournament_users WHERE id = ?", String::class.java, tuId)!!

    private fun tournamentIdOf(tuId: Long): Long =
        jdbcTemplate.queryForObject("SELECT tournament_id FROM tournament_users WHERE id = ?", Long::class.javaObjectType, tuId)!!

    private fun completedAtSet(tuId: Long): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT completed_at IS NOT NULL FROM tournament_users WHERE id = ?",
            Boolean::class.javaObjectType,
            tuId,
        )!!

    private fun deleted(tuId: Long): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT deleted_at IS NOT NULL FROM tournament_users WHERE id = ?",
            Boolean::class.javaObjectType,
            tuId,
        )!!

    private fun historyTournamentId(historyId: Long): Long =
        jdbcTemplate.queryForObject("SELECT tournament_id FROM tournament_histories WHERE id = ?", Long::class.javaObjectType, historyId)!!

    private fun historyTournamentUserId(historyId: Long): Long =
        jdbcTemplate.queryForObject(
            "SELECT tournament_user_id FROM tournament_histories WHERE id = ?",
            Long::class.javaObjectType,
            historyId,
        )!!

    private fun historyDeleted(historyId: Long): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT deleted_at IS NOT NULL FROM tournament_histories WHERE id = ?",
            Boolean::class.javaObjectType,
            historyId,
        )!!
}
