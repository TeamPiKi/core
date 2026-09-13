package com.depromeet.piki.tournament.migration

import java.sql.Connection
import org.slf4j.LoggerFactory

// #1027 Phase 2 백필 — CLONE 이 들고 있던 플레이(진행) 상태를 참여 행(tournament_users)으로 평탄화한다.
//
// 클론(source_tournament_id 있는 tournaments 행)은 도메인상 "한 사람의 판"이다. 그 사람의 status·completed_at·
// 이력이 지금은 클론 쪽에 매달려 있는데, 이걸 그 사람의 ROOT 참여 행으로 옮긴다. 그러면 Phase 3 의 새 읽기
// (참여 행 하나 = 그 사람의 진행)가 성립하고 클론 행은 리다이렉트 껍데기만 남는다(제거는 Phase 4).
//
// data-driven: 숫자·id 를 박지 않고 실제 행 관계로 판정한다 — dev/prod 데이터 분포가 달라도 같은 규칙으로 돈다.
//   클론의 **참여자 전원**을 각각 본다(owner 하나가 아니라 — 옛 모델은 초대코드로 클론에 직접 참여하는 등
//   클론당 참여자 2+ 를 허용했다). 각 참여자가 ROOT 에 별도 참여 행을 갖나?
//     - 있고 그 행에 자기 플레이가 없음(초대 멤버) → 병합: 클론 플레이를 그 ROOT 행으로 옮기고 클론 TU soft-delete
//     - 없음(링크 게스트) → 재지향: 그 클론 TU 의 tournament_id 를 ROOT 로, 이력은 tuId 기준으로 옮김
//     - 있고 이미 자기 플레이가 있음(self·중복) → 스킵: ROOT 플레이가 정본, 중복 클론 TU·이력 soft-delete
//
// 방어: 재지향 시 uk 충돌은 가정하지 않고 위반 시 예외로 중단한다(dangling root 등도 자연히 조회에서 빠진다).
// Flyway Java 마이그레이션은 기본 트랜잭션 안에서 돌아, 예외 시 전체 롤백된다 — 반쯤 평탄화된 채 남지 않는다.
// dev 배포가 prod 보다 먼저라, dev 의 지저분한 데이터에서 엣지가 먼저 드러난다(#1027: dev 클론 202 다중참가로 실증).
class CloneFlattenBackfill(
    private val conn: Connection,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run() {
        // #1027: 완료 판정을 status 로 일원화하므로, 먼저 "completed_at 있으면 status=COMPLETED" 불변식을 세운다.
        // Phase 1 컬럼 추가는 기존 완료 행도 PENDING 으로 채웠다. 이후 소유자·클론 승계가 대부분을 덮지만, 어느 승계에도
        // 안 걸리는 잔재 완료 행(레거시 비-소유자 ROOT 완료 등)까지 여기서 확정해 status 기반 완료 조회가 빠뜨리지 않게 한다.
        val completedFixed = ensureCompletedStatusForCompletedAt()
        val ownerStatus = backfillRootOwnerStatus()
        val participants = loadCloneParticipants()

        var merged = 0
        var repointed = 0
        var selfCloneSkipped = 0
        // 클론의 owner 하나가 아니라 참여자 전원을 평탄화한다. 옛 모델은 초대코드로 클론에 직접 참여하거나(#1027 dev
        // 실데이터: 루트 소유자가 자기 클론에도 참여) 클론당 참여자가 2+ 인 형태를 허용했으므로, "클론당 참여자=1"
        // 가정을 두지 않고 각 참여자를 독립적으로 그 사람의 ROOT 참여로 옮긴다.
        for (participant in participants) {
            // 활성 ROOT 참여 행이 있으면 멤버(병합) 또는 이미 자기 플레이가 있으면(self·중복) 스킵, 없으면 링크 게스트(재지향).
            findActiveRootParticipation(participant.rootId, participant.userId, participant.cloneTuId)?.let { rootTU ->
                if (rootTU.hasOwnPlay) {
                    skipSelfClone(participant)
                    selfCloneSkipped++
                } else {
                    mergeMemberClone(participant, rootTU.id)
                    merged++
                }
            } ?: run {
                repointLinkGuest(participant)
                repointed++
            }
        }

        log.info(
            "CLONE 평탄화 백필 완료(#1027 Phase 2): completedStatusFixed={} rootOwnerStatus={} cloneParticipants={} merged(멤버)={} repointed(링크게스트)={} selfCloneSkipped={}",
            completedFixed,
            ownerStatus,
            participants.size,
            merged,
            repointed,
            selfCloneSkipped,
        )
    }

    // 완료 판정 단일화(#1027) — completed_at 이 있는데 status 가 COMPLETED 가 아닌 행을 COMPLETED 로 맞춘다.
    // 이후 status 기반 완료 조회(find/countCompletedByTournamentId)가 completedAt 기반과 어긋나지 않게 하는 불변식 세팅.
    private fun ensureCompletedStatusForCompletedAt(): Int =
        conn.prepareStatement(
            """
            UPDATE tournament_users
            SET status = 'COMPLETED', updated_at = NOW(6)
            WHERE completed_at IS NOT NULL AND status <> 'COMPLETED'
            """.trimIndent(),
        ).use { it.executeUpdate() }

    // ROOT 주최자의 참여 status = ROOT 토너먼트 status. (주최자는 ROOT 를 직접 플레이하므로 그 진행이 곧 참여 진행.)
    // ROOT 에 참여만 하고 안 논 멤버는 status='PENDING' 기본값 그대로 둔다(플레이 없음). 멤버가 플레이했으면
    // 클론이 생겼고, 그 진행은 아래 클론 병합에서 그 사람의 ROOT 참여 행으로 옮겨진다.
    private fun backfillRootOwnerStatus(): Int =
        conn.prepareStatement(
            """
            UPDATE tournament_users tu
            JOIN tournaments t
              ON t.owner_tournament_user_id = tu.id
             AND t.source_tournament_id IS NULL
             AND t.deleted_at IS NULL
            SET tu.status = t.status, tu.updated_at = NOW(6)
            """.trimIndent(),
        ).use { it.executeUpdate() }

    private data class Clone(
        val cloneId: Long,
        val rootId: Long,
        val cloneStatus: String,
        val cloneTuId: Long,
        val userId: ByteArray,
    )

    private data class RootParticipation(
        val id: Long,
        val hasOwnPlay: Boolean,
    )

    // 살아있는 클론의 참여 행을 **전원** 읽는다(owner 하나가 아니라). 대부분 클론은 참여자가 1(그 owner)이지만,
    // 옛 모델은 초대코드로 클론에 직접 참여하는 등 2+ 참여자를 허용했다 — 그 형태까지 각 참여자를 독립 평탄화한다.
    // 각 Clone 엔트리는 "한 클론 참여자" 를 뜻한다(cloneTuId·userId 가 그 참여자). non-deleted 인 것만 대상.
    private fun loadCloneParticipants(): List<Clone> =
        conn.prepareStatement(
            """
            SELECT c.id AS clone_id, c.source_tournament_id AS root_id, c.status AS clone_status,
                   tu.id AS clone_tu_id, tu.user_id AS user_id
            FROM tournaments c
            JOIN tournament_users tu ON tu.tournament_id = c.id
            WHERE c.source_tournament_id IS NOT NULL
              AND c.deleted_at IS NULL
              AND tu.deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Clone(
                                cloneId = rs.getLong("clone_id"),
                                rootId = rs.getLong("root_id"),
                                cloneStatus = rs.getString("clone_status"),
                                cloneTuId = rs.getLong("clone_tu_id"),
                                userId = rs.getBytes("user_id"),
                            ),
                        )
                    }
                }
            }
        }

    // userId 가 ROOT 에 갖는 활성 참여 행(클론 TU 제외). 없으면 링크 게스트, 있으면 멤버.
    // hasOwnPlay = 그 ROOT 행이 이미 자기 플레이를 가짐(주최자가 ROOT 를 완주했거나 이력이 있음) → self-clone 판별.
    private fun findActiveRootParticipation(
        rootId: Long,
        userId: ByteArray,
        cloneTuId: Long,
    ): RootParticipation? =
        conn.prepareStatement(
            """
            SELECT tu.id AS id,
                   (tu.completed_at IS NOT NULL
                    OR EXISTS (SELECT 1 FROM tournament_histories h
                               WHERE h.tournament_user_id = tu.id AND h.deleted_at IS NULL)) AS has_own_play
            FROM tournament_users tu
            WHERE tu.tournament_id = ? AND tu.user_id = ? AND tu.id <> ? AND tu.deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.setLong(1, rootId)
            stmt.setBytes(2, userId)
            stmt.setLong(3, cloneTuId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) RootParticipation(rs.getLong("id"), rs.getBoolean("has_own_play")) else null
            }
        }

    // 링크 게스트: ROOT 에 자기 참여 행이 없는 클론 참여자 → 그 클론 TU 를 ROOT 로 옮기고 그 사람 이력의
    // tournament_id 도 ROOT 로. 이력 이관은 tournament_user_id(그 클론 TU) 기준으로 좁혀, 같은 클론의 다른
    // 참여자 이력을 함께 끌어오지 않는다(다중참가 클론 대비. 클론 이력은 항상 tuId 가 채워져 있어 누락 없음).
    // 재지향 전, ROOT 에 같은 유저의 다른 행(soft-deleted 포함)이 있으면 uk_tournament_users 충돌 → 중단.
    private fun repointLinkGuest(clone: Clone) {
        val collision =
            conn.prepareStatement(
                "SELECT COUNT(*) FROM tournament_users WHERE tournament_id = ? AND user_id = ? AND id <> ?",
            ).use { stmt ->
                stmt.setLong(1, clone.rootId)
                stmt.setBytes(2, clone.userId)
                stmt.setLong(3, clone.cloneTuId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        check(collision == 0) {
            "링크 게스트 재지향 충돌: ROOT ${clone.rootId} 에 유저의 기존 행 존재(soft-deleted 포함) — 클론 ${clone.cloneId}(#1027 백필 중단)"
        }

        conn.prepareStatement(
            "UPDATE tournament_users SET tournament_id = ?, status = ?, updated_at = NOW(6) WHERE id = ?",
        ).use { stmt ->
            stmt.setLong(1, clone.rootId)
            stmt.setString(2, clone.cloneStatus)
            stmt.setLong(3, clone.cloneTuId)
            stmt.executeUpdate()
        }
        conn.prepareStatement(
            "UPDATE tournament_histories SET tournament_id = ?, updated_at = NOW(6) WHERE tournament_id = ? AND tournament_user_id = ? AND deleted_at IS NULL",
        ).use { stmt ->
            stmt.setLong(1, clone.rootId)
            stmt.setLong(2, clone.cloneId)
            stmt.setLong(3, clone.cloneTuId)
            stmt.executeUpdate()
        }
    }

    // 초대 멤버: ROOT 참여 행(roster)에 클론 플레이를 병합한다 — status·completed_at 을 옮기고, 이력을
    // (clone_id, clone_tu) → (root_id, root_tu) 로 재지향, 클론 TU soft-delete.
    private fun mergeMemberClone(
        clone: Clone,
        rootTuId: Long,
    ) {
        conn.prepareStatement(
            """
            UPDATE tournament_users root_tu
            JOIN tournament_users clone_tu ON clone_tu.id = ?
            SET root_tu.status = ?, root_tu.completed_at = clone_tu.completed_at, root_tu.updated_at = NOW(6)
            WHERE root_tu.id = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.setLong(1, clone.cloneTuId)
            stmt.setString(2, clone.cloneStatus)
            stmt.setLong(3, rootTuId)
            stmt.executeUpdate()
        }
        conn.prepareStatement(
            "UPDATE tournament_histories SET tournament_id = ?, tournament_user_id = ?, updated_at = NOW(6) WHERE tournament_user_id = ? AND deleted_at IS NULL",
        ).use { stmt ->
            stmt.setLong(1, clone.rootId)
            stmt.setLong(2, rootTuId)
            stmt.setLong(3, clone.cloneTuId)
            stmt.executeUpdate()
        }
        softDeleteTournamentUser(clone.cloneTuId)
    }

    // self-clone: 주최자가 자기 링크로 만든 중복 판. ROOT 플레이가 정본이라 덮지 않고, 중복 클론 TU·이력을 soft-delete.
    private fun skipSelfClone(clone: Clone) {
        conn.prepareStatement(
            "UPDATE tournament_histories SET deleted_at = NOW(6), updated_at = NOW(6) WHERE tournament_user_id = ? AND deleted_at IS NULL",
        ).use { stmt ->
            stmt.setLong(1, clone.cloneTuId)
            stmt.executeUpdate()
        }
        softDeleteTournamentUser(clone.cloneTuId)
    }

    private fun softDeleteTournamentUser(tuId: Long) {
        conn.prepareStatement(
            "UPDATE tournament_users SET deleted_at = NOW(6), updated_at = NOW(6) WHERE id = ? AND deleted_at IS NULL",
        ).use { stmt ->
            stmt.setLong(1, tuId)
            stmt.executeUpdate()
        }
    }
}
