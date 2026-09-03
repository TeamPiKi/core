package com.depromeet.piki.item.service

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemLinkJpaRepository
import com.depromeet.piki.item.repository.ItemLinkRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubItemParsingWorker
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.service.WishPersistenceService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 공유 정체성 활성화(#825 3단계)의 계약 검증 — 별칭 히트 재사용·진행 중 합류·앞문 409·병합(재부모화)은 전부
// DB 문장과 여러 빈의 협업이라 통합으로만 검증 가능하다.
//
// 클래스 @Transactional 을 쓰지 않는다 — recorder 의 canonical claim·병합은 자기 트랜잭션(TransactionTemplate)으로
// 돌고 native 문장이 영속성 컨텍스트를 우회하므로, 테스트가 트랜잭션을 공유하면 stale 읽기로 운영과 다른 분기를
// 탄다(ItemIdentityRecordingIntegrationTest 와 같은 이유). 각 테스트가 자기 행을 finally 에서 명시 정리한다.
//
// 각 테스트가 본문에서 stubItemParsingWorker.enabled=false 로 워커를 무력화한다 — 비트랜잭션이라 커밋된 PENDING 을
// 라이브 디스패처(@Scheduled)가 집어 전이시키면 "진행 중" 전제가 무너져 flake 한다. 상태 시딩(READY)은 도메인 전이
// 대신 JDBC 로 한다: markProcessing 은 PENDING 전제라 디스패처의 claim 과 경합하면 check 로 깨진다.
class ItemSharingIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemIdentityRecorder: ItemIdentityRecorder

    @Autowired private lateinit var itemSharingService: ItemSharingService

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var itemLinkRepository: ItemLinkRepository

    @Autowired private lateinit var itemLinkJpaRepository: ItemLinkJpaRepository

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var stubItemParsingWorker: StubItemParsingWorker

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `같은 링크의 두 번째 등록은 새 item 없이 기존 item 의 진행 중 파싱에 합류한다`() {
        stubItemParsingWorker.enabled = false
        val userA = newMember()
        val userB = newMember()
        val url = "https://www.musinsa.com/products/8100001"
        val first = wishPersistenceService.persist(userA, ProductLink.parse(url))
        try {
            // 첫 등록이 PENDING(진행 중) — 두 번째 등록은 새 item·새 작업 없이 같은 snapshot 을 함께 기다린다(#826).
            val second = wishPersistenceService.persist(userB, ProductLink.parse(url))
            assertEquals(first.item.getId(), second.item.getId())
            assertEquals(first.snapshot.getId(), second.snapshot.getId())
        } finally {
            stubItemParsingWorker.enabled = true
            cleanup(listOf(first.item.getId()), listOf(userA, userB))
        }
    }

    @Test
    fun `기계 READY 가 신선하면 재등록이 파싱 없이 그 버전을 재사용하고 갱신 권고 없이 내려간다`() {
        stubItemParsingWorker.enabled = false
        val userA = newMember()
        val userB = newMember()
        val url = "https://www.musinsa.com/products/8100002"
        val first = wishPersistenceService.persist(userA, ProductLink.parse(url))
        try {
            seedMachineReady(first.snapshot.getId(), extractedHoursAgo = 1)

            val second = wishPersistenceService.persist(userB, ProductLink.parse(url))
            assertEquals(first.item.getId(), second.item.getId())
            assertEquals(first.snapshot.getId(), second.snapshot.getId())
            assertEquals(ItemStatus.READY, second.snapshot.status)
            assertTrue(second.reused)
            assertFalse(second.refreshNeeded)
            // 새 PENDING 이 만들어지지 않았다 — 재사용은 파싱 비용 0 이 본질.
            assertNull(itemSnapshotRepository.findLatestInProgressByItemId(first.item.getId()))
        } finally {
            stubItemParsingWorker.enabled = true
            cleanup(listOf(first.item.getId()), listOf(userA, userB))
        }
    }

    @Test
    fun `낡은 READY 도 재사용하되 refreshNeeded 로 갱신을 권고한다 - 등록은 자동 재추출을 만들지 않는다`() {
        stubItemParsingWorker.enabled = false
        val userA = newMember()
        val userB = newMember()
        val url = "https://www.musinsa.com/products/8100003"
        val first = wishPersistenceService.persist(userA, ProductLink.parse(url))
        try {
            // 갱신 권고 임계(24h) 밖의 기계 READY — 그래도 그 값에 붙고, 재추출 여부는 사용자 선택(#853).
            seedMachineReady(first.snapshot.getId(), extractedHoursAgo = 25)

            val second = wishPersistenceService.persist(userB, ProductLink.parse(url))
            assertEquals(first.item.getId(), second.item.getId())
            assertEquals(first.snapshot.getId(), second.snapshot.getId())
            assertTrue(second.reused)
            assertTrue(second.refreshNeeded)
            // 등록이 새 파싱을 만들지 않았다 — 위시 행 수에 비례하는 자동 부하를 만들지 않는 것이 본질.
            assertNull(itemSnapshotRepository.findLatestInProgressByItemId(first.item.getId()))
        } finally {
            stubItemParsingWorker.enabled = true
            cleanup(listOf(first.item.getId()), listOf(userA, userB))
        }
    }

    @Test
    fun `캐시 재사용 등록의 HTTP 응답에 reused·refreshNeeded 플래그가 내려간다`() {
        stubItemParsingWorker.enabled = false
        val userA = newMember()
        val userB = newMember()
        val url = "https://www.musinsa.com/products/8100006"
        val first = wishPersistenceService.persist(userA, ProductLink.parse(url))
        try {
            seedMachineReady(first.snapshot.getId(), extractedHoursAgo = 25)

            val mockMvc =
                MockMvcBuilders
                    .webAppContextSetup(webApplicationContext)
                    .apply<DefaultMockMvcBuilder>(springSecurity())
                    .build()
            val auth = "Bearer ${jwtProvider.generateAccessToken(userB, IdentityType.MEMBER)}"
            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url": "$url"}"""),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.item.status").value("READY"))
                .andExpect(jsonPath("$.data.reused").value(true))
                .andExpect(jsonPath("$.data.refreshNeeded").value(true))
        } finally {
            stubItemParsingWorker.enabled = true
            cleanup(listOf(first.item.getId()), listOf(userA, userB))
        }
    }

    @Test
    fun `같은 사용자가 같은 상품을 다시 담으면 409 - 링크 모양이 달라도 정체성 기준`() {
        stubItemParsingWorker.enabled = false
        val user = newMember()
        val first = wishPersistenceService.persist(user, ProductLink.parse("https://www.musinsa.com/products/8100004"))
        try {
            val mockMvc =
                MockMvcBuilders
                    .webAppContextSetup(webApplicationContext)
                    .apply<DefaultMockMvcBuilder>(springSecurity())
                    .build()
            val auth = "Bearer ${jwtProvider.generateAccessToken(user, IdentityType.MEMBER)}"

            // override 몰이라 추적 쿼리가 달라도 같은 정체성으로 정규화된다 — 문자열 비교였다면 통과했을 재등록이 막힌다.
            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url": "https://www.musinsa.com/products/8100004?utm_source=kakao"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("WISH-009"))
                .andExpect(jsonPath("$.detail").value("이미 위시리스트에 등록된 상품이에요."))
        } finally {
            stubItemParsingWorker.enabled = true
            cleanup(listOf(first.item.getId()), listOf(user))
        }
    }

    @Test
    fun `귀결점 충돌은 병합된다 - 스냅샷 재부모화 + 별칭 이관 + 임시 item 폐기 + 이후 등록은 승자에 붙음`() {
        stubItemParsingWorker.enabled = false
        val userA = newMember()
        val userB = newMember()
        val userC = newMember()
        // 서로 다른 단축 모양이라 별칭 미스 — 각자 item 이 생기고, 파싱 완료 시 같은 귀결점으로 충돌한다(뒷문).
        val first = wishPersistenceService.persist(userA, ProductLink.parse("https://musinsa.onelink.me/PvkC/share0001"))
        val second = wishPersistenceService.persist(userB, ProductLink.parse("https://musinsa.onelink.me/PvkC/share0002"))
        val winnerId = first.item.getId()
        val loserId = second.item.getId()
        try {
            val finalUrl = "https://www.musinsa.com/products/8100005"
            itemIdentityRecorder.recordParsingIdentity(winnerId, finalUrl)
            itemIdentityRecorder.recordParsingIdentity(loserId, finalUrl)

            // 진 쪽 버전이 승자 소속으로 재부모화됐다 — 위시·출전은 snapshot 만 참조하므로 포인터 수정 없이 자동 추종.
            val loserSnapshot = itemSnapshotRepository.findById(second.snapshot.getId())
            assertNotNull(loserSnapshot)
            assertEquals(winnerId, loserSnapshot.itemId)
            // 별칭이 전부 승자 소속으로 이관됐고 빈 껍데기 item 은 soft delete 됐다.
            assertTrue(itemLinkRepository.findByItemId(loserId).isEmpty())
            val deletedAt =
                jdbcTemplate.queryForObject(
                    "SELECT deleted_at FROM items WHERE id = ?",
                    java.sql.Timestamp::class.java,
                    loserId,
                )
            assertNotNull(deletedAt)
            // 이후 진 쪽 단축 모양으로 재등록해도 승자 item 에 붙는다 — 병합이 정체성 조회 공간을 하나로 만든다.
            val resolved = itemSharingService.resolveExistingItem(ProductLink.parse("https://musinsa.onelink.me/PvkC/share0002"))
            assertEquals(winnerId, resolved?.getId())
            // 실제 등록 흐름(persist)까지 승자에 붙는지 확인 — 조회 공간만이 아니라 attach 도 병합 결과를 따른다.
            val third = wishPersistenceService.persist(userC, ProductLink.parse("https://musinsa.onelink.me/PvkC/share0002"))
            assertEquals(winnerId, third.item.getId())
        } finally {
            stubItemParsingWorker.enabled = true
            cleanup(listOf(winnerId, loserId), listOf(userA, userB, userC))
        }
    }

    private fun newMember(): UUID {
        val userId = UUID.randomUUID()
        userJpaRepository.save(
            User(
                id = userId,
                nickname = "곰${userId.toString().take(5)}",
                profileImage = "https://cdn.example.com/p.jpg",
                identityType = IdentityType.MEMBER,
            ),
        )
        return userId
    }

    // 파싱 완료(기계 READY)를 JDBC 로 시딩한다 — 도메인 전이(markProcessing→markReady)는 라이브 디스패처의
    // claim 과 경합하면 상태 check 로 깨지므로, 결정론을 위해 행을 직접 원하는 상태로 둔다.
    private fun seedMachineReady(
        snapshotId: Long,
        extractedHoursAgo: Long,
    ) {
        jdbcTemplate.update(
            """
            UPDATE item_snapshots
            SET status = 'READY', name = '공유 상품', image_url = 'https://img.example.com/s.png',
                price = 10000, currency = 'KRW', source = 'SERVER',
                extracted_at = DATE_SUB(NOW(6), INTERVAL ? HOUR), updated_at = NOW(6)
            WHERE id = ?
            """,
            extractedHoursAgo,
            snapshotId,
        )
    }

    private fun cleanup(
        itemIds: List<Long>,
        userIds: List<UUID>,
    ) {
        itemIds.forEach { id ->
            // 리포지토리 @Modifying 대신 JDBC — 비트랜잭션 테스트라 트랜잭션 없는 @Modifying 호출은 실패한다.
            jdbcTemplate.update("DELETE FROM item_links WHERE item_id = ?", id)
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", id)
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", id)
        }
        userIds.forEach { userId ->
            jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(userId))
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }
}
