package com.depromeet.piki.metrics.dashboard

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

// 운영 통계 집계 전용 읽기 저장소. 모든 시각 컬럼(created_at 등)은 JVM 기본 TZ(UTC)로 저장되므로, 서비스가 조회 구간(KST)을
// UTC LocalDateTime(from·to)으로 변환해 넘긴다. "구간 내"는 [from, to), "구간 전"은 < from. KST 시간대별 집계는 created_at 에
// +9h 를 더해 버킷팅한다. user_daily_activity.active_date 만은 이미 KST 날짜로 적재돼 그대로 쓴다.
//
// 개발진(내부 유저) 제외 토글: per-user 집계는 exclude=true 일 때 developers 명단을 NOT IN 으로 뺀다(/admin/metrics 토글, 기본 제외).
// developers 는 개발진 user_id 를 보관한다(이메일은 user_details 에 있으니 중복 저장 안 함 — 넣을 때 이메일로 1회 해석).
// user_id 를 직접 가진 테이블은 그 컬럼으로, 토너먼트 생성/플레이처럼 user 를 tournament_users 경유로만 아는 테이블은
// notInternalViaTu 로 건다. user 차원이 없는 집계(파싱 item_snapshots · 공지 announcements)는 토글과 무관하게 그대로 둔다.
@Repository
class MetricsRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    // ---- 가입자 ----
    fun countActiveUsersBefore(
        from: LocalDateTime,
        exclude: Boolean,
    ): Long = count("SELECT COUNT(*) FROM users WHERE created_at < ? AND deleted_at IS NULL${notInternal(exclude, "id")}", ts(from))

    fun countActiveUsersWithin(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long =
        count(
            "SELECT COUNT(*) FROM users WHERE created_at >= ? AND created_at < ? AND deleted_at IS NULL${notInternal(exclude, "id")}",
            ts(from),
            ts(to),
        )

    // 가입자 기준 누적 사용자 수 — 활성(deleted_at) 필터·기간 필터 없이 지금까지 생성된 모든 users 행을 센다
    // (탈퇴 소프트삭제 포함, 회원·게스트 포함). exclude=true 면 개발진(developers) 제외. 사용자 수 마일스톤 판정용.
    fun countAllUsers(exclude: Boolean): Long = count("SELECT COUNT(*) FROM users WHERE 1 = 1${notInternal(exclude, "id")}")

    fun countWithinByIdentityType(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Map<String, Long> =
        keyCounts(
            "SELECT identity_type, COUNT(*) FROM users " +
                "WHERE created_at >= ? AND created_at < ? AND deleted_at IS NULL${notInternal(exclude, "id")} GROUP BY identity_type",
            ts(from),
            ts(to),
        )

    fun countSignupsByProvider(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Map<String, Long> =
        keyCounts(
            "SELECT provider, COUNT(*) FROM user_details " +
                "WHERE created_at >= ? AND created_at < ?${notInternal(exclude, "user_id")} GROUP BY provider",
            ts(from),
            ts(to),
        )

    // 게스트→회원 전환 근사 — 소셜 연결(user_details) 시각이 가입(users.created_at)보다 1분 이상 늦으면, 게스트로 먼저
    // 존재하다 구간 중 연결한 것으로 본다. 동시 신규가입(거의 동시각)과 구분한다.
    fun countGuestToMemberConversions(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long =
        count(
            """
            SELECT COUNT(*) FROM user_details ud JOIN users u ON u.id = ud.user_id
            WHERE ud.created_at >= ? AND ud.created_at < ? AND u.created_at < ud.created_at - INTERVAL 1 MINUTE${notInternal(exclude, "ud.user_id")}
            """.trimIndent(),
            ts(from),
            ts(to),
        )

    // ---- 위시 ----
    fun countWishes(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long = count("SELECT COUNT(*) FROM wishes WHERE created_at >= ? AND created_at < ?${notInternal(exclude, "user_id")}", ts(from), ts(to))

    // source_url IS NULL = 이미지 등록, NOT NULL = URL 등록. wish 는 snapshot_id 로 정규화돼 item 정체성은
    // item_snapshots.item_id 를 거쳐 items 에 도달한다(item_id 컬럼 제거됨).
    fun countWishesBySource(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Pair<Long, Long> {
        val byImage =
            keyCounts(
                """
                SELECT i.source_url IS NULL AS is_image, COUNT(*)
                FROM wishes w
                JOIN item_snapshots s ON s.id = w.snapshot_id
                JOIN items i ON i.id = s.item_id
                WHERE w.created_at >= ? AND w.created_at < ?${notInternal(exclude, "w.user_id")} GROUP BY is_image
                """.trimIndent(),
                ts(from),
                ts(to),
            )
        val url = byImage["0"] ?: 0L
        val image = byImage["1"] ?: 0L
        return url to image
    }

    // 현재 활성 위시(stock) — 구간 무관, deleted_at IS NULL 인 "지금 살아있는 위시"를 url/image 로 나눈다. 개발진 토글 적용.
    // 유입(countWishes/countWishesBySource, flow)과 달리 삭제된 위시는 빠지고 기간 필터도 없다(현재 재고 스냅샷).
    fun countActiveWishesBySource(exclude: Boolean): Pair<Long, Long> {
        val byImage =
            keyCounts(
                """
                SELECT i.source_url IS NULL AS is_image, COUNT(*)
                FROM wishes w
                JOIN item_snapshots s ON s.id = w.snapshot_id
                JOIN items i ON i.id = s.item_id
                WHERE w.deleted_at IS NULL${notInternal(exclude, "w.user_id")} GROUP BY is_image
                """.trimIndent(),
            )
        return (byImage["0"] ?: 0L) to (byImage["1"] ?: 0L)
    }

    // 파싱(item_snapshots READY/FAILED)을 출처별로 나눈다 — 아이템이 위시로 참조되면 '위시 파싱'(등록+새로고침 전부),
    // 아니면 '토너먼트 파싱'(토너먼트 전용 아이템). 위시·토너먼트가 별도 item 을 만들어 item 단위로 출처가 갈린다.
    // 파싱은 user 차원이 없어 개발진 제외는 못 한다(전체 파싱과 동일한 인지된 한계) — 토글과 무관하게 개발진 파싱도 포함된다.
    //
    // 한계: 출처 분류는 스냅샷 생성 시점 고정이 아니라 조회 시점의 "이 아이템에 위시가 있나"로 판정한다. 현재는 위시·토너먼트가
    // 서로 다른 item 을 만들어(공유 경로 없음) 한 아이템이 두 출처를 오가지 않으므로 과거 구간 집계가 안정적이다. 다만 앞으로
    // 토너먼트 아이템을 위시로 자동 편입하는 등 두 흐름이 item 을 공유하게 되면, 위시가 생긴 순간 그 아이템의 과거 스냅샷이
    // 토너먼트→위시로 재분류돼 지난 구간 수치가 달라진다 — 그 시점엔 출처를 스냅샷 생성 시점에 영속화(컬럼)해 고정해야 한다.
    // (스냅샷 활성-위시로만 좁히면 새로고침·비활성 버전이 빠져 '위시 파싱=위시 아이템 전체 추출' 의미가 깨지므로 쓰지 않는다.)
    fun countParsingBySource(
        from: LocalDateTime,
        to: LocalDateTime,
    ): ParsingBySource {
        val rows =
            jdbcTemplate.query(
                """
                SELECT
                  EXISTS(SELECT 1 FROM wishes w JOIN item_snapshots s2 ON s2.id = w.snapshot_id WHERE s2.item_id = s.item_id) AS is_wish,
                  s.status, COUNT(*)
                FROM item_snapshots s
                WHERE s.created_at >= ? AND s.created_at < ? AND s.status IN ('READY','FAILED')
                GROUP BY is_wish, s.status
                """.trimIndent(),
                { rs, _ -> Triple(rs.getBoolean(1), rs.getString(2), rs.getLong(3)) },
                ts(from),
                ts(to),
            )
        fun pick(
            isWish: Boolean,
            status: String,
        ): Long = rows.firstOrNull { it.first == isWish && it.second == status }?.third ?: 0L
        return ParsingBySource(
            wishReady = pick(true, "READY"),
            wishFailed = pick(true, "FAILED"),
            tournamentReady = pick(false, "READY"),
            tournamentFailed = pick(false, "FAILED"),
        )
    }

    // ---- 토너먼트 ----
    // tournaments 는 생성자를 owner_tournament_user_id(tournament_users.id)로만 알아, 개발진 제외는 그 TU 경유로 건다.
    fun countTournamentsCreated(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long =
        count(
            "SELECT COUNT(*) FROM tournaments WHERE created_at >= ? AND created_at < ?${notInternalViaTu(exclude, "owner_tournament_user_id", false)}",
            ts(from),
            ts(to),
        )

    fun countTournamentParticipants(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long =
        count(
            "SELECT COUNT(DISTINCT user_id) FROM tournament_users WHERE created_at >= ? AND created_at < ?${notInternal(exclude, "user_id")}",
            ts(from),
            ts(to),
        )

    fun countTournamentItems(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long =
        count(
            "SELECT COUNT(*) FROM tournament_items WHERE created_at >= ? AND created_at < ?${notInternal(exclude, "user_id")}",
            ts(from),
            ts(to),
        )

    // tournaments 에 상태 전이 시각이 없어, 완료는 tournament_users.completed_at 으로 센다(참가자별 완료).
    fun countTournamentCompleted(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long =
        count(
            "SELECT COUNT(*) FROM tournament_users WHERE completed_at >= ? AND completed_at < ?${notInternal(exclude, "user_id")}",
            ts(from),
            ts(to),
        )

    // 플레이 활동량 = 라운드 픽 1건당 history 1행. tournament_user_id 는 nullable(기존 행은 NULL) — old 행은 보존하고
    // 값이 있는 행 중 개발진 TU 만 제외한다.
    fun countTournamentPlays(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long =
        count(
            "SELECT COUNT(*) FROM tournament_histories " +
                "WHERE created_at >= ? AND created_at < ?${notInternalViaTu(exclude, "tournament_user_id", true)}",
            ts(from),
            ts(to),
        )

    // ---- 푸시 도달 가능 (현재 상태, 구간 무관) ----
    fun countPushReachableUsers(exclude: Boolean): Long =
        count("SELECT COUNT(DISTINCT user_id) FROM user_devices WHERE deleted_at IS NULL${notInternal(exclude, "user_id")}")

    // ---- 리텐션 / DAU ----
    // 구간에 가입한 코호트(탈퇴 포함 — 가입 사실 기준). 리텐션 분모.
    fun countSignupsInWindow(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long = count("SELECT COUNT(*) FROM users WHERE created_at >= ? AND created_at < ?${notInternal(exclude, "id")}", ts(from), ts(to))

    // 구간에 가입한 유저 중, 각자의 "가입 다음날(KST)"에 활동 기록이 있는 수 = D1 재방문. active_date 를 구간시작+1 로
    // 고정하면 다일자 구간에서 중·후반 가입자의 D1 이 통째로 누락돼 비율이 과소집계된다 → 가입자별 다음날로 조인한다.
    // created_at(UTC)+9h 의 날짜 = 가입 KST 날짜, +1일 = 그 다음날. active_date 는 이미 KST 날짜로 적재돼 직접 비교된다.
    fun countD1Returned(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long =
        count(
            """
            SELECT COUNT(DISTINCT u.id)
            FROM users u
            JOIN user_daily_activity uda
              ON uda.user_id = u.id
             AND uda.active_date = DATE(u.created_at + INTERVAL 9 HOUR) + INTERVAL 1 DAY
            WHERE u.created_at >= ? AND u.created_at < ?${notInternal(exclude, "u.id")}
            """.trimIndent(),
            ts(from),
            ts(to),
        )

    // 구간이 덮는 KST 날짜들의 DAU 만(전체 기간이 아니라 선택 구간으로 한정).
    fun dailyActiveUsers(
        fromDate: LocalDate,
        toDate: LocalDate,
        exclude: Boolean,
    ): List<MetricsSnapshot.DateCount> =
        jdbcTemplate.query(
            "SELECT active_date, COUNT(*) FROM user_daily_activity " +
                "WHERE active_date BETWEEN ? AND ?${notInternal(exclude, "user_id")} GROUP BY active_date ORDER BY active_date",
            { rs, _ -> MetricsSnapshot.DateCount(rs.getDate(1).toLocalDate(), rs.getLong(2)) },
            java.sql.Date.valueOf(fromDate),
            java.sql.Date.valueOf(toDate),
        )

    // ---- 푸시 히스토리 / CTR 근사 ----
    fun notificationsByType(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Map<String, Long> =
        keyCounts(
            "SELECT type, COUNT(*) FROM notifications WHERE created_at >= ? AND created_at < ?${notInternal(exclude, "user_id")} GROUP BY type",
            ts(from),
            ts(to),
        )

    // (total, is_read 합) — CTR 근사 계산용.
    fun notificationReadApprox(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Pair<Long, Long> =
        jdbcTemplate
            .query(
                "SELECT COUNT(*), COALESCE(SUM(is_read), 0) FROM notifications " +
                    "WHERE created_at >= ? AND created_at < ?${notInternal(exclude, "user_id")}",
                { rs, _ -> rs.getLong(1) to rs.getLong(2) },
                ts(from),
                ts(to),
            ).first()

    // 공지 발송(announcements)은 전 유저 대상 단건 집계라 user 차원이 없어 개발진 제외 불가(인지된 한계).
    fun announcementDelivery(
        from: LocalDateTime,
        to: LocalDateTime,
    ): Triple<Long, Long, Long> =
        jdbcTemplate
            .query(
                """
                SELECT COALESCE(SUM(success_count),0), COALESCE(SUM(failure_count),0), COALESCE(SUM(skipped_count),0)
                FROM announcements WHERE sent_at >= ? AND sent_at < ?
                """.trimIndent(),
                { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) },
                ts(from),
                ts(to),
            ).first()

    // ---- 시간대별(KST) ----
    fun hourlySignups(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Map<Int, Long> {
        val result = linkedMapOf<Int, Long>()
        jdbcTemplate.query(
            """
            SELECT HOUR(created_at + INTERVAL 9 HOUR) AS h, COUNT(*)
            FROM users WHERE created_at >= ? AND created_at < ?${notInternal(exclude, "id")}
            GROUP BY h ORDER BY h
            """.trimIndent(),
            { rs, _ -> result[rs.getInt(1)] = rs.getLong(2) },
            ts(from),
            ts(to),
        )
        return result
    }

    // ---- 리포트 전용 추가 집계 ----

    // 누적 회원 provider 분포(asOf 시점까지 연결된 활성 user_details). 리포트에서 %로 환산한다.
    fun countCumulativeByProvider(
        asOf: LocalDateTime,
        exclude: Boolean,
    ): Map<String, Long> =
        keyCounts(
            "SELECT provider, COUNT(*) FROM user_details " +
                "WHERE created_at < ? AND deleted_at IS NULL${notInternal(exclude, "user_id")} GROUP BY provider",
            ts(asOf),
        )

    // WAU — 구간 내 DISTINCT 활성 유저 수. dau 리스트의 단순 합은 같은 유저의 여러 날을 중복 카운트하므로 별도 distinct.
    fun countWeeklyActiveUsers(
        fromDate: LocalDate,
        toDate: LocalDate,
        exclude: Boolean,
    ): Long =
        count(
            "SELECT COUNT(DISTINCT user_id) FROM user_daily_activity " +
                "WHERE active_date BETWEEN ? AND ?${notInternal(exclude, "user_id")}",
            java.sql.Date.valueOf(fromDate),
            java.sql.Date.valueOf(toDate),
        )

    // 구간 내 탈퇴 수(순증 계산용). users.deleted_at 이 구간에 든 행.
    fun countWithdrawals(
        from: LocalDateTime,
        to: LocalDateTime,
        exclude: Boolean,
    ): Long =
        count(
            "SELECT COUNT(*) FROM users WHERE deleted_at >= ? AND deleted_at < ?${notInternal(exclude, "id")}",
            ts(from),
            ts(to),
        )

    // 파싱 평균 시도 횟수(추출 건강도). 확정 상태(READY/FAILED)만. 대상이 없으면 null.
    fun avgParsingAttempts(
        from: LocalDateTime,
        to: LocalDateTime,
    ): Double? =
        jdbcTemplate.queryForObject(
            "SELECT AVG(attempt_count) FROM item_snapshots " +
                "WHERE created_at >= ? AND created_at < ? AND status IN ('READY','FAILED')",
            Double::class.javaObjectType,
            ts(from),
            ts(to),
        )

    // exclude=true 면 "AND <column> NOT IN (개발진 user_id)" 조각을, false(포함)면 빈 문자열을 돌려준다.
    // developers 가 비어 있으면 NOT IN (빈 집합)이라 아무도 제외되지 않는다(SQL 안전).
    private fun notInternal(
        exclude: Boolean,
        column: String,
    ): String = if (exclude) " AND $column NOT IN ($DEVELOPER_IDS)" else ""

    // tournament_users.id 를 가리키는 컬럼용(tournaments.owner_tournament_user_id · tournament_histories.tournament_user_id).
    // nullable=true(히스토리)면 NULL 행은 보존하고 값이 있는 개발진 TU 만 제외한다.
    private fun notInternalViaTu(
        exclude: Boolean,
        column: String,
        nullable: Boolean,
    ): String {
        if (!exclude) return ""
        val tu = "SELECT id FROM tournament_users WHERE user_id IN ($DEVELOPER_IDS)"
        return if (nullable) " AND ($column IS NULL OR $column NOT IN ($tu))" else " AND $column NOT IN ($tu)"
    }

    private fun count(
        sql: String,
        vararg args: Any,
    ): Long = jdbcTemplate.queryForObject(sql, Long::class.java, *args) ?: 0L

    private fun keyCounts(
        sql: String,
        vararg args: Any,
    ): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        jdbcTemplate.query(sql, { rs, _ -> result[rs.getString(1)] = rs.getLong(2) }, *args)
        return result
    }

    private fun ts(value: LocalDateTime): Timestamp = Timestamp.valueOf(value)

    companion object {
        // 개발진 user_id 명단(developers 테이블). "개발진 포함" 토글이 꺼져 있을 때(기본) 이 user_id 들을 집계에서 뺀다.
        // 명단 추가는 이메일로 1회 해석해 넣는다: INSERT INTO developers (user_id) SELECT user_id FROM user_details WHERE email = '...'.
        private const val DEVELOPER_IDS = "SELECT user_id FROM developers"
    }
}

// 파싱 출처별 집계 결과 — 위시(등록+새로고침) vs 토너먼트(전용 아이템), 각 READY/FAILED.
data class ParsingBySource(
    val wishReady: Long,
    val wishFailed: Long,
    val tournamentReady: Long,
    val tournamentFailed: Long,
)
