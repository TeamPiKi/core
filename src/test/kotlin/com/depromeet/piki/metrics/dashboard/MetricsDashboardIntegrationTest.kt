package com.depromeet.piki.metrics.dashboard

import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

// 운영 통계 대시보드 — 조회 구간(KST from~to)의 양쪽 경계 분리와 신규 user_daily_activity 리텐션 조인을 실데이터 +
// Testcontainers MySQL 로 검증한다. created_at 은 UTC 저장이라, 구간 6/20 13:00~18:00 KST = 6/20 04:00~09:00 UTC 기준으로
// 구간 전(6/19 14:00)·구간 내(06:00 UTC)·구간 후(11:00 UTC) 유저를 심어 양쪽 경계가 맞는지 본다. 시딩 안 한 지표는
// 0 으로 떨어져 쿼리 실행 무결성도 함께 확인한다. admin 게이트는 admin.local-bypass 로 우회된다.
@Transactional
class MetricsDashboardIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private fun mockMvc() =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private val beforeWindow = "2026-06-19 14:00:00" // 구간 전
    private val withinWindow = "2026-06-20 06:00:00" // 구간 내(= 15:00 KST)
    private val afterWindow = "2026-06-20 11:00:00" // 구간 후(= 20:00 KST) → 제외돼야 함

    private fun insertUser(
        id: UUID,
        identityType: String,
        createdAt: String,
    ) = jdbcTemplate.update(
        "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
        uuidToBytes(id),
        id.toString().take(10),
        identityType,
        Timestamp.valueOf(createdAt),
        Timestamp.valueOf(createdAt),
    )

    @Test
    fun `조회 구간의 시작·종료 경계로 가입자와 리텐션이 정확히 집계된다`() {
        // 구간 전 활성 유저 2
        repeat(2) { insertUser(UUID.randomUUID(), "MEMBER", beforeWindow) }

        // 구간 내 유저 3 (회원 2 · 게스트 1)
        val member1 = UUID.randomUUID()
        val member2 = UUID.randomUUID()
        val guest1 = UUID.randomUUID()
        insertUser(member1, "MEMBER", withinWindow)
        insertUser(member2, "MEMBER", withinWindow)
        insertUser(guest1, "GUEST", withinWindow)

        // 구간 후 유저 1 — 종료 경계(18:00) 이후라 집계에서 빠져야 한다
        insertUser(UUID.randomUUID(), "MEMBER", afterWindow)

        // 소셜 연결(provider) — 가입과 거의 동시각이라 전환(1분 이상 갭) 아님
        jdbcTemplate.update(
            "INSERT INTO user_details (user_id, email, provider, social_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            uuidToBytes(member1), "a@t.com", "KAKAO", UUID.randomUUID().toString(), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow),
        )
        jdbcTemplate.update(
            "INSERT INTO user_details (user_id, email, provider, social_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            uuidToBytes(member2), "b@t.com", "GOOGLE", UUID.randomUUID().toString(), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow),
        )

        // 위시 — URL 아이템 1 · 이미지(source_url NULL) 아이템 1, 구간 내. snapshot_id 로 참조(정규화).
        jdbcTemplate.update("INSERT INTO items (id, source_url, created_at, updated_at) VALUES (1001, 'https://shop.example/a', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO items (id, created_at, updated_at) VALUES (1002, ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO item_snapshots (id, item_id, status, created_at, updated_at) VALUES (2001, 1001, 'READY', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO item_snapshots (id, item_id, status, created_at, updated_at) VALUES (2002, 1002, 'FAILED', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO wishes (user_id, snapshot_id, created_at, updated_at) VALUES (?, 2001, ?, ?)", uuidToBytes(member1), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO wishes (user_id, snapshot_id, created_at, updated_at) VALUES (?, 2002, ?, ?)", uuidToBytes(member1), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))

        // 리텐션 — 구간(6/20)에 가입한 3명 중 2명이 다음날(6/21 KST) 활동
        jdbcTemplate.update("INSERT INTO user_daily_activity (user_id, active_date, created_at) VALUES (?, ?, NOW(6))", uuidToBytes(member1), Date.valueOf(LocalDate.of(2026, 6, 21)))
        jdbcTemplate.update("INSERT INTO user_daily_activity (user_id, active_date, created_at) VALUES (?, ?, NOW(6))", uuidToBytes(member2), Date.valueOf(LocalDate.of(2026, 6, 21)))

        val result =
            mockMvc()
                .perform(
                    get("/admin/metrics")
                        .param("from", "2026-06-20T13:00")
                        .param("to", "2026-06-20T18:00"),
                ).andExpect(status().isOk)
                .andExpect(view().name("admin/metrics"))
                .andReturn()

        val snapshot = result.modelAndView!!.model["snapshot"] as MetricsSnapshot

        assertEquals(LocalDateTime.of(2026, 6, 20, 13, 0), snapshot.from)
        assertEquals(LocalDateTime.of(2026, 6, 20, 18, 0), snapshot.to)

        // 가입 — 구간 전/내, 구간 후(afterWindow)는 제외
        assertEquals(2, snapshot.signup.before)
        assertEquals(3, snapshot.signup.within)
        assertEquals(2, snapshot.signup.withinMembers)
        assertEquals(1, snapshot.signup.withinGuests)
        assertEquals(1L, snapshot.signup.byProvider["KAKAO"])
        assertEquals(1L, snapshot.signup.byProvider["GOOGLE"])
        assertEquals(0, snapshot.signup.guestToMemberConversions)

        // 위시 — 유입(flow): 구간 내 담긴 2건(URL 1 · 이미지 1)
        assertEquals(2, snapshot.wish.total)
        assertEquals(1, snapshot.wish.fromUrl)
        assertEquals(1, snapshot.wish.fromImage)
        // 활성(stock): 둘 다 삭제 안 됐으니 flow 와 동일하게 2건
        assertEquals(2, snapshot.wish.activeTotal)
        assertEquals(1, snapshot.wish.activeFromUrl)
        assertEquals(1, snapshot.wish.activeFromImage)
        // 파싱: 둘 다 위시 아이템이라 위시 파싱에만 잡히고 토너먼트 파싱은 0
        assertEquals(1, snapshot.wish.parsedReady)
        assertEquals(1, snapshot.wish.parsedFailed)
        assertEquals(50, snapshot.wish.parseSuccessRate)
        assertEquals(1, snapshot.wish.wishParsedReady)
        assertEquals(1, snapshot.wish.wishParsedFailed)
        assertEquals(0, snapshot.wish.tournamentParsedReady)
        assertEquals(0, snapshot.wish.tournamentParsedFailed)

        // 리텐션
        assertEquals(3, snapshot.retention.cohortSignups)
        assertEquals(2, snapshot.retention.d1Returned)
        assertEquals(66, snapshot.retention.d1Rate)

        // 미시딩 지표 — 쿼리 실행 무결성(0)
        assertEquals(0, snapshot.tournament.created)
        assertEquals(0, snapshot.pushReachableUsers)
        assertEquals(0, snapshot.push.notificationsTotal)
    }

    @Test
    fun `활성 위시는 삭제된 위시를 빼고, 파싱은 위시-토너먼트 출처로 분리 집계된다`() {
        val member = UUID.randomUUID()
        insertUser(member, "MEMBER", withinWindow)

        // 위시 아이템 — 활성 2건(URL·이미지) + 삭제 1건(URL). 셋 다 위시로 참조돼 파싱은 '위시 파싱'.
        jdbcTemplate.update("INSERT INTO items (id, source_url, created_at, updated_at) VALUES (3001, 'https://shop.example/a', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO items (id, created_at, updated_at) VALUES (3002, ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow)) // 이미지(source_url NULL)
        jdbcTemplate.update("INSERT INTO items (id, source_url, created_at, updated_at) VALUES (3003, 'https://shop.example/c', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO item_snapshots (id, item_id, status, created_at, updated_at) VALUES (3101, 3001, 'READY', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO item_snapshots (id, item_id, status, created_at, updated_at) VALUES (3102, 3002, 'READY', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO item_snapshots (id, item_id, status, created_at, updated_at) VALUES (3103, 3003, 'FAILED', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO wishes (user_id, snapshot_id, created_at, updated_at) VALUES (?, 3101, ?, ?)", uuidToBytes(member), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO wishes (user_id, snapshot_id, created_at, updated_at) VALUES (?, 3102, ?, ?)", uuidToBytes(member), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        // 삭제된 위시 — 활성(stock)에선 빠지지만 유입(flow)엔 잡히고, 파싱은 '위시 파싱'으로 남는다(아이템이 위시로 참조됨).
        jdbcTemplate.update("INSERT INTO wishes (user_id, snapshot_id, created_at, updated_at, deleted_at) VALUES (?, 3103, ?, ?, ?)", uuidToBytes(member), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))

        // 토너먼트 전용 아이템 — 위시가 없어 '토너먼트 파싱'으로 잡힌다.
        jdbcTemplate.update("INSERT INTO items (id, source_url, created_at, updated_at) VALUES (3004, 'https://shop.example/d', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO item_snapshots (id, item_id, status, created_at, updated_at) VALUES (3104, 3004, 'READY', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO tournament_items (tournament_id, user_id, snapshot_id, created_at, updated_at) VALUES (8500, ?, 3104, ?, ?)", uuidToBytes(member), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))

        val snapshot =
            mockMvc()
                .perform(get("/admin/metrics").param("from", "2026-06-20T13:00").param("to", "2026-06-20T18:00"))
                .andExpect(status().isOk)
                .andReturn()
                .modelAndView!!
                .model["snapshot"] as MetricsSnapshot

        // 유입(flow)은 삭제 포함 3건, 활성(stock)은 삭제 뺀 2건 — 둘이 다르다.
        assertEquals(3, snapshot.wish.total)
        assertEquals(2, snapshot.wish.activeTotal)
        assertEquals(1, snapshot.wish.activeFromUrl)
        assertEquals(1, snapshot.wish.activeFromImage)

        // 파싱 출처 분리 — 위시(등록, 삭제된 위시 포함) 2R/1F, 토너먼트 1R/0F
        assertEquals(2, snapshot.wish.wishParsedReady)
        assertEquals(1, snapshot.wish.wishParsedFailed)
        assertEquals(1, snapshot.wish.tournamentParsedReady)
        assertEquals(0, snapshot.wish.tournamentParsedFailed)
        // 전체·성공률
        assertEquals(3, snapshot.wish.parsedReady)
        assertEquals(1, snapshot.wish.parsedFailed)
        assertEquals(75, snapshot.wish.parseSuccessRate)
        assertEquals(66, snapshot.wish.wishParseSuccessRate)
        assertEquals(100, snapshot.wish.tournamentParseSuccessRate)
    }

    @Test
    fun `다일자 구간에서도 가입자별 가입 다음날 기준으로 D1 이 집계된다`() {
        // 6/20 가입 → 6/21 활동, 6/21 가입 → 6/22 활동 (각자 가입 다음날). active_date 를 구간시작+1 로 고정하면
        // 6/21 가입자의 D1(6/22)이 누락돼 과소집계되는데, 가입자별 다음날 조인이면 둘 다 잡힌다.
        val day20 = UUID.randomUUID()
        val day21 = UUID.randomUUID()
        insertUser(day20, "MEMBER", "2026-06-19 16:00:00") // KST 6/20 01:00
        insertUser(day21, "MEMBER", "2026-06-20 16:00:00") // KST 6/21 01:00
        jdbcTemplate.update("INSERT INTO user_daily_activity (user_id, active_date, created_at) VALUES (?, ?, NOW(6))", uuidToBytes(day20), Date.valueOf(LocalDate.of(2026, 6, 21)))
        jdbcTemplate.update("INSERT INTO user_daily_activity (user_id, active_date, created_at) VALUES (?, ?, NOW(6))", uuidToBytes(day21), Date.valueOf(LocalDate.of(2026, 6, 22)))

        val snapshot =
            mockMvc()
                .perform(get("/admin/metrics").param("from", "2026-06-20T00:00").param("to", "2026-06-22T00:00"))
                .andExpect(status().isOk)
                .andReturn()
                .modelAndView!!
                .model["snapshot"] as MetricsSnapshot

        assertEquals(2, snapshot.retention.cohortSignups)
        assertEquals(2, snapshot.retention.d1Returned)
        assertEquals(100, snapshot.retention.d1Rate)
    }

    // 개발진은 developers 명단(user_id)으로 식별된다. 같은 활동을 한 일반 유저 1·개발진 1을 심고,
    // excludeInternal=true(기본)면 개발진이 빠져 1명분만, false(토글 포함)면 둘 다 2명분이 집계되는지 본다.
    private fun snapshotVia(excludeInternal: Boolean): MetricsSnapshot =
        mockMvc()
            .perform(
                get("/admin/metrics")
                    .param("from", "2026-06-20T13:00")
                    .param("to", "2026-06-20T18:00")
                    .param("excludeInternal", excludeInternal.toString()),
            ).andExpect(status().isOk)
            .andReturn()
            .modelAndView!!
            .model["snapshot"] as MetricsSnapshot

    @Test
    fun `개발진 포함 토글에 따라 developers 명단의 활동이 빠지거나 포함된다`() {
        val normal = UUID.randomUUID()
        val dev = UUID.randomUUID()
        insertUser(normal, "MEMBER", withinWindow)
        insertUser(dev, "MEMBER", withinWindow)
        // 개발진을 developers 명단에 user_id 로 등록(운영에선 이메일로 1회 해석해 넣는 그 user_id).
        jdbcTemplate.update("INSERT INTO developers (user_id) VALUES (?)", uuidToBytes(dev))

        // 위시 — 각 유저 1건(snapshot_id 로 참조).
        jdbcTemplate.update("INSERT INTO items (id, source_url, created_at, updated_at) VALUES (7001, 'https://shop.example/n', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO items (id, source_url, created_at, updated_at) VALUES (7002, 'https://shop.example/d', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO item_snapshots (id, item_id, status, created_at, updated_at) VALUES (7101, 7001, 'READY', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO item_snapshots (id, item_id, status, created_at, updated_at) VALUES (7102, 7002, 'READY', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO wishes (user_id, snapshot_id, created_at, updated_at) VALUES (?, 7101, ?, ?)", uuidToBytes(normal), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO wishes (user_id, snapshot_id, created_at, updated_at) VALUES (?, 7102, ?, ?)", uuidToBytes(dev), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))

        // 푸시 도달 가능 — 각자 활성 디바이스 1개.
        jdbcTemplate.update("INSERT INTO user_devices (user_id, device_id, fcm_token, created_at, updated_at) VALUES (?, 'dev-n', 'tok-n', ?, ?)", uuidToBytes(normal), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO user_devices (user_id, device_id, fcm_token, created_at, updated_at) VALUES (?, 'dev-d', 'tok-d', ?, ?)", uuidToBytes(dev), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))

        // DAU — 각자 구간 날짜(6/20 KST)에 활동.
        jdbcTemplate.update("INSERT INTO user_daily_activity (user_id, active_date, created_at) VALUES (?, ?, NOW(6))", uuidToBytes(normal), Date.valueOf(LocalDate.of(2026, 6, 20)))
        jdbcTemplate.update("INSERT INTO user_daily_activity (user_id, active_date, created_at) VALUES (?, ?, NOW(6))", uuidToBytes(dev), Date.valueOf(LocalDate.of(2026, 6, 20)))

        // 기본(제외) — 개발진(dev)이 빠져 일반 유저 1명분만. 활성 위시도 개발진 제외가 적용된다(w.user_id 기준).
        val excluded = snapshotVia(excludeInternal = true)
        assertEquals(1, excluded.signup.within)
        assertEquals(1, excluded.wish.total)
        assertEquals(1, excluded.wish.activeTotal)
        assertEquals(1, excluded.wish.activeFromUrl)
        assertEquals(1, excluded.pushReachableUsers)
        assertEquals(1, excluded.retention.dau.single { it.date == LocalDate.of(2026, 6, 20) }.count)

        // 토글 포함 — 개발진까지 2명분.
        val included = snapshotVia(excludeInternal = false)
        assertEquals(2, included.signup.within)
        assertEquals(2, included.wish.total)
        assertEquals(2, included.wish.activeTotal)
        assertEquals(2, included.wish.activeFromUrl)
        assertEquals(2, included.pushReachableUsers)
        assertEquals(2, included.retention.dau.single { it.date == LocalDate.of(2026, 6, 20) }.count)
    }

    @Test
    fun `공지 발송 집계는 KST sent_at 으로 조회돼 구간 안의 성공·실패·미도달이 잡힌다`() {
        // announcements.sent_at 은 (다른 테이블과 달리) KST wall-clock 으로 저장된다. 조회 구간이 KST 13:00~18:00 이면
        // sent_at=15:00(KST)인 공지가 잡혀야 한다. UTC 로 변환해 조회하던 버그(9시간 어긋나 항상 0/0/0)의 회귀 방지.
        jdbcTemplate.update(
            "INSERT INTO announcements (title, body, target, status, sent_at, success_count, failure_count, skipped_count, created_at, updated_at) " +
                "VALUES ('공지', '본문', '토큰 보유자 전체', 'SENT', ?, 5, 2, 1, ?, ?)",
            Timestamp.valueOf("2026-06-20 15:00:00"),
            Timestamp.valueOf(withinWindow),
            Timestamp.valueOf(withinWindow),
        )

        val snapshot =
            mockMvc()
                .perform(get("/admin/metrics").param("from", "2026-06-20T13:00").param("to", "2026-06-20T18:00"))
                .andExpect(status().isOk)
                .andReturn()
                .modelAndView!!
                .model["snapshot"] as MetricsSnapshot

        assertEquals(5, snapshot.push.deliverySuccess)
        assertEquals(2, snapshot.push.deliveryFailure)
        assertEquals(1, snapshot.push.deliverySkipped)
    }

    @Test
    fun `토글이 via-TU 토너먼트(생성·플레이) 제외에도 적용되고 tournament_user_id 가 NULL 인 history 는 보존된다`() {
        // tournaments·tournament_histories 는 user_id 를 직접 안 갖고 tournament_users 경유로만 알아, 제외가 가장 복잡한 경로다.
        // 개발진 소유 토너먼트 1 · 일반 토너먼트 1 · tournament_user_id NULL(옛 행) history 1 을 심어,
        // excludeInternal=true 면 개발진 생성·플레이만 빠지고 NULL history 는 보존되는지 본다.
        val normal = UUID.randomUUID()
        val dev = UUID.randomUUID()
        insertUser(normal, "MEMBER", withinWindow)
        insertUser(dev, "MEMBER", withinWindow)
        jdbcTemplate.update("INSERT INTO developers (user_id) VALUES (?)", uuidToBytes(dev))

        // tournament_users — 각자 1명(id 명시).
        jdbcTemplate.update("INSERT INTO tournament_users (id, tournament_id, user_id, created_at, updated_at) VALUES (9001, 8001, ?, ?, ?)", uuidToBytes(normal), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO tournament_users (id, tournament_id, user_id, created_at, updated_at) VALUES (9002, 8002, ?, ?, ?)", uuidToBytes(dev), Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))

        // tournaments — 생성자(owner_tournament_user_id) 경유로 제외. invite_code 는 활성 토너먼트 간 UNIQUE 라 행마다 다른 값.
        jdbcTemplate.update("INSERT INTO tournaments (id, owner_tournament_user_id, name, status, invite_code, created_at, updated_at) VALUES (8001, 9001, 'n', 'PENDING', '111111', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO tournaments (id, owner_tournament_user_id, name, status, invite_code, created_at, updated_at) VALUES (8002, 9002, 'd', 'PENDING', '222222', ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))

        // tournament_histories — 일반 TU / 개발진 TU / NULL(옛 행). 플레이는 라운드 픽 1건당 1행.
        val histCols = "(tournament_id, current_round, first_tournament_item_id, second_tournament_item_id, selected_tournament_item_id, tournament_user_id, created_at, updated_at)"
        jdbcTemplate.update("INSERT INTO tournament_histories $histCols VALUES (8001, 1, 1, 2, 1, 9001, ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO tournament_histories $histCols VALUES (8002, 1, 1, 2, 1, 9002, ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))
        jdbcTemplate.update("INSERT INTO tournament_histories $histCols VALUES (8001, 1, 1, 2, 1, NULL, ?, ?)", Timestamp.valueOf(withinWindow), Timestamp.valueOf(withinWindow))

        // 제외 — 개발진 생성·플레이·참여가 빠지고, NULL history 는 살아남는다.
        val excluded = snapshotVia(excludeInternal = true)
        assertEquals(1, excluded.tournament.created) // 개발진 소유(8002) 제외
        assertEquals(1, excluded.tournament.participants) // 개발진 TU(9002) 제외
        assertEquals(2, excluded.tournament.plays) // 개발진 history 제외, NULL·일반 history 보존

        // 포함 — 전부 집계.
        val included = snapshotVia(excludeInternal = false)
        assertEquals(2, included.tournament.created)
        assertEquals(2, included.tournament.participants)
        assertEquals(3, included.tournament.plays)
    }
}
