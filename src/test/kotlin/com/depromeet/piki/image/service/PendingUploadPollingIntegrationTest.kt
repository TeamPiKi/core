package com.depromeet.piki.image.service

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.storage.ImageStorageException
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.StubImageSnapshotExtractor
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.repository.TournamentItemJpaRepository
import com.depromeet.piki.tournament.service.TournamentItemService
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.service.WishlistService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 이미지 등록 v2 폴링 백스톱 — 클라 confirm 없이 "발급된 pending 을 서버 폴링이 스스로 확인해 등록"하는 경로.
// 폴링 @Scheduled 자동 실행은 IntegrationTestSupport 에서 꺼져 있고(stub exists 기본 true 로 인한 오염 방지),
// 여기선 pollOnce() 를 직접 호출해 결정적으로 검증한다. @Transactional 없이 실제 커밋 + finally 정리.
class PendingUploadPollingIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var wishlistService: WishlistService

    @Autowired
    private lateinit var tournamentItemService: TournamentItemService

    @Autowired
    private lateinit var pollingScheduler: PendingUploadPollingScheduler

    @Autowired
    private lateinit var stubImageStorage: StubImageStorage

    @Autowired
    private lateinit var stubImageSnapshotExtractor: StubImageSnapshotExtractor

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired
    private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired
    private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `폴링이 업로드된 위시 pending 을 등록해 위시가 생긴다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            seedExtractor()
            // 발급 — pending 만 커밋되고 위시는 아직 없다(stub exists 기본 true 라 폴링이 "올라왔다"고 보고 등록한다).
            wishlistService.presignImageUploads(listOf("image/png", "image/jpeg"), userId)
            assertEquals(0, wishCount(userId))
            makePollable(userId)

            pollingScheduler.pollOnce()

            assertEquals(2, wishCount(userId))
            // 등록된 pending 은 claim(삭제)됐다.
            assertEquals(0, pendingCount(userId))
        } finally {
            cleanupWishes(userId)
        }
    }

    @Test
    fun `방금 발급된 pending 은 첫 확인 시각 전이라 폴링이 건드리지 않는다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            seedExtractor()
            wishlistService.presignImageUploads(listOf("image/png"), userId)

            pollingScheduler.pollOnce()

            assertEquals(0, wishCount(userId))
            assertEquals(1, pendingCount(userId))
        } finally {
            cleanupWishes(userId)
        }
    }

    @Test
    fun `안 올라온 pending 은 오래 기다릴수록 다음 확인이 멀어진다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            seedExtractor()
            stubImageStorage.existsBehavior = { false }
            wishlistService.presignImageUploads(listOf("image/png"), userId)

            issuedAgo(userId, Duration.ofSeconds(10))
            pollingScheduler.pollOnce()
            val afterShortWait = waitUntilNextCheck(userId)

            issuedAgo(userId, Duration.ofMinutes(10))
            pollingScheduler.pollOnce()
            val afterLongWait = waitUntilNextCheck(userId)

            assertTrue(afterLongWait > afterShortWait, "$afterShortWait -> $afterLongWait")
            assertTrue(afterLongWait <= PendingUpload.MAX_CHECK_INTERVAL, "상한을 넘었다: $afterLongWait")
        } finally {
            stubImageStorage.existsBehavior = stubImageStorage.defaultExistsBehavior
            cleanupWishes(userId)
        }
    }

    @Test
    fun `한 배치에서 일부만 올라오면 올라온 것만 등록하고 나머지는 다음 확인을 미룬다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            seedExtractor()
            val uploads = wishlistService.presignImageUploads(listOf("image/png", "image/jpeg"), userId)
            val uploadedKey = uploads.first().imageKey
            stubImageStorage.existsBehavior = { key -> key == uploadedKey }
            issuedAgo(userId, Duration.ofSeconds(10))

            pollingScheduler.pollOnce()

            assertEquals(1, wishCount(userId))
            assertEquals(1, pendingCount(userId))
            assertTrue(
                waitUntilNextCheck(userId) > Duration.ZERO,
                "남은 pending 이 미뤄지지 않으면 만료까지 매 주기 HEAD 를 맞는다",
            )
        } finally {
            stubImageStorage.existsBehavior = stubImageStorage.defaultExistsBehavior
            cleanupWishes(userId)
        }
    }

    @Test
    fun `HEAD 가 일시 실패하면 폴링이 그룹을 등록하지 않고 보류한다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            seedExtractor()
            // S3 HEAD 가 일시 장애로 예외를 던지는 상황 — "안 올라옴"으로 확정하지 않고 그룹 전체를 보류해야 한다.
            stubImageStorage.existsBehavior = { throw ImageStorageException.existsCheckFailed() }
            wishlistService.presignImageUploads(listOf("image/png", "image/jpeg"), userId)
            makePollable(userId)

            pollingScheduler.pollOnce()

            // 판단 보류 — 등록도 정리도 안 되고 pending 이 그대로 남아 다음 폴링 대상이 된다.
            assertEquals(0, wishCount(userId))
            assertEquals(2, pendingCount(userId))
        } finally {
            stubImageStorage.existsBehavior = stubImageStorage.defaultExistsBehavior
            cleanupWishes(userId)
        }
    }

    @Test
    fun `폴링은 아직 안 올라온 위시 pending 을 등록하지 않고 매핑을 남긴다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // S3 에 아직 안 올라온 상황 — HEAD 가 false 를 돌려주면 폴링은 등록하지 않고 다음 폴링으로 미룬다.
            stubImageStorage.existsBehavior = { false }
            wishlistService.presignImageUploads(listOf("image/png"), userId)
            makePollable(userId)

            pollingScheduler.pollOnce()

            assertEquals(0, wishCount(userId))
            // 등록 안 됐으니 pending 은 그대로 남아 다음 폴링 대상이다.
            assertEquals(1, pendingCount(userId))
        } finally {
            stubImageStorage.existsBehavior = stubImageStorage.defaultExistsBehavior
            cleanupWishes(userId)
        }
    }

    @Test
    fun `업로드 없이 만료된 pending 은 폴링이 정리하고 위시를 만들지 않는다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // 업로드하지 않은 채 유효기간이 지난 발급 — HEAD 가 false 라 폴링이 등록 없이 매핑만 정리한다.
            stubImageStorage.existsBehavior = { false }
            wishlistService.presignImageUploads(listOf("image/png"), userId)
            jdbcTemplate.update(
                "UPDATE pending_uploads SET expires_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusMinutes(1),
                uuidToBytes(userId),
            )

            pollingScheduler.pollOnce()

            assertEquals(0, wishCount(userId))
            assertEquals(0, pendingCount(userId))
        } finally {
            stubImageStorage.existsBehavior = stubImageStorage.defaultExistsBehavior
            cleanupWishes(userId)
        }
    }

    @Test
    fun `업로드됐으나 등록이 밀린 채 만료된 pending 은 폴링이 등록해 유실되지 않는다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            seedExtractor()
            // 업로드는 성공(exists 기본 true)했으나 등록이 밀린 채 유효기간이 지난 상황 — 만료돼도 유실 대신 마지막 등록을 시도해야 한다.
            wishlistService.presignImageUploads(listOf("image/png"), userId)
            jdbcTemplate.update(
                "UPDATE pending_uploads SET expires_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusMinutes(1),
                uuidToBytes(userId),
            )

            pollingScheduler.pollOnce()

            assertEquals(1, wishCount(userId))
            assertEquals(0, pendingCount(userId))
        } finally {
            cleanupWishes(userId)
        }
    }

    @Test
    fun `confirm 으로 등록된 뒤 폴링이 돌아도 위시가 중복 생기지 않는다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            seedExtractor()
            val keys = wishlistService.presignImageUploads(listOf("image/png"), userId).map { it.imageKey }
            // confirm 이 pending 을 claim(삭제)하며 등록한다.
            wishlistService.confirmImageRegistration(keys, userId)
            assertEquals(1, wishCount(userId))

            // 폴링 — confirm 이 이미 claim 해 pending 이 없으므로 등록할 게 없다(멱등).
            pollingScheduler.pollOnce()

            assertEquals(1, wishCount(userId))
            assertEquals(0, pendingCount(userId))
        } finally {
            cleanupWishes(userId)
        }
    }

    @Test
    fun `폴링이 업로드된 토너먼트 pending 을 등록해 아이템이 생긴다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertMember(ownerId)
        var tournamentId = 0L
        try {
            seedExtractor()
            tournamentId = createTournament(mockMvc, ownerId)
            tournamentItemService.presignImageUploads(ownerId, tournamentId, listOf("image/png", "image/jpeg"))
            assertEquals(0, tournamentItemCount(tournamentId))
            makePollable(ownerId)

            pollingScheduler.pollOnce()

            assertEquals(2, tournamentItemCount(tournamentId))
        } finally {
            cleanupTournament(ownerId, tournamentId)
        }
    }

    @Test
    fun `정원이 부족하면 폴링도 confirm 처럼 배치 전량 거부하고 부분 등록하지 않는다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertMember(ownerId)
        var tournamentId = 0L
        try {
            seedExtractor()
            tournamentId = createTournament(mockMvc, ownerId)
            // 정원(32) 임박까지 미리 채운다 — 30개. 남은 자리는 2개뿐.
            fillTournament(tournamentId, ownerId, 30)
            // 3개 발급 → 폴링 등록 시 30+3=33 > 32. 단건씩이면 2개 부분 등록되지만, 배치 원자성이면 전량 거부여야 한다.
            tournamentItemService.presignImageUploads(ownerId, tournamentId, listOf("image/png", "image/jpeg", "image/webp"))
            makePollable(ownerId)

            pollingScheduler.pollOnce()

            // 부분 등록(2개) 없이 30 그대로 — confirm 의 all-or-nothing 과 같은 정원 판정.
            assertEquals(30, tournamentItemCount(tournamentId))
            // 실패한 그룹의 pending 은 삭제되지 않고 남아 다음 폴링이 재시도할 수 있어야 한다(registerUploaded 는 만료 전이라 폐기하지 않는다).
            assertEquals(3, pendingCount(ownerId))
        } finally {
            cleanupTournament(ownerId, tournamentId)
        }
    }

    @Test
    fun `업로드된 채 만료됐으나 정원 초과면 폴링이 폐기하고 부분 등록하지 않는다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertMember(ownerId)
        var tournamentId = 0L
        try {
            seedExtractor()
            tournamentId = createTournament(mockMvc, ownerId)
            // 정원(32) 임박까지 채우고, 3개를 발급+업로드한 뒤 만료시킨다. 만료 경로도 배치라 30+3=33>32 면 전량 폐기여야 한다.
            fillTournament(tournamentId, ownerId, 30)
            tournamentItemService.presignImageUploads(ownerId, tournamentId, listOf("image/png", "image/jpeg", "image/webp"))
            jdbcTemplate.update(
                "UPDATE pending_uploads SET expires_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusMinutes(1),
                uuidToBytes(ownerId),
            )

            pollingScheduler.pollOnce()

            // 만료 경로가 단건 등록이면 2개가 부분 등록되지만, 배치 원자성이면 전량 거부(폐기)여야 한다.
            assertEquals(30, tournamentItemCount(tournamentId))
            // 영구 사유(정원 초과)로 폐기됐으므로 pending 도 남지 않는다.
            assertEquals(0, pendingCount(ownerId))
        } finally {
            cleanupTournament(ownerId, tournamentId)
        }
    }

    // ---- 헬퍼 ----

    private fun makePollable(userId: UUID) = issuedAgo(userId, Duration.ofMinutes(1))

    private fun issuedAgo(
        userId: UUID,
        ago: Duration,
    ) {
        val issuedAt = LocalDateTime.now().minus(ago)
        jdbcTemplate.update(
            "UPDATE pending_uploads SET created_at = ?, next_check_at = ? WHERE user_id = ?",
            issuedAt,
            issuedAt,
            uuidToBytes(userId),
        )
    }

    private fun waitUntilNextCheck(userId: UUID): Duration {
        val nextCheckAt =
            jdbcTemplate.queryForObject(
                "SELECT next_check_at FROM pending_uploads WHERE user_id = ? ORDER BY id LIMIT 1",
                LocalDateTime::class.java,
                uuidToBytes(userId),
            ) ?: error("pending 이 없다")
        return Duration.between(LocalDateTime.now(), nextCheckAt)
    }

    // 토너먼트를 지정 개수의 READY 아이템으로 미리 채운다(정원 경계 시나리오용). 정원 카운트는 tournament_items 행 수만 본다.
    private fun fillTournament(
        tournamentId: Long,
        ownerId: UUID,
        count: Int,
    ) {
        val items = itemJpaRepository.saveAll((1..count).map { Item() })
        val snapshots =
            itemSnapshotJpaRepository.saveAll(
                items.map {
                    ItemSnapshot(
                        itemId = it.getId(),
                        name = "fill",
                        price = 1_000,
                        currency = "KRW",
                        status = ItemStatus.READY,
                        extractedAt = LocalDateTime.now(),
                    )
                },
            )
        tournamentItemJpaRepository.saveAll(
            snapshots.map { TournamentItem(tournamentId = tournamentId, userId = ownerId, snapshotId = it.getId()) },
        )
    }

    // 폴링 등록 후 자동 dispatch(ItemParsingScheduler)가 파싱을 돌리므로, 깨끗한 추출 결과를 세팅해 워커 노이즈를 없앤다.
    private fun seedExtractor() {
        stubImageSnapshotExtractor.build = { StubImageSnapshotExtractor.defaultSnapshot() }
    }

    private fun createTournament(
        mockMvc: MockMvc,
        ownerId: UUID,
    ): Long {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/tournaments")
                        .contentType(MediaType.APPLICATION_JSON)
                        // 토너먼트 생성은 회원 전용(#339). 이 테스트의 관심사는 폴링 백스톱이지 게스트 권한이 아니다.
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(ownerId)}")
                        .content("""{"name":"폴링토너먼트"}"""),
                ).andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        return objectMapper
            .readTree(response)
            .path("data")
            .path("tournamentId")
            .asLong()
    }

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun insertMember(userId: UUID) = insertUser(userId, IdentityType.MEMBER.name)

    private fun insertUser(
        userId: UUID,
        identityType: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            identityType,
        )
    }

    private fun memberToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)

    private fun wishCount(userId: UUID): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishes WHERE user_id = ?", Int::class.java, uuidToBytes(userId)) ?: 0

    private fun pendingCount(userId: UUID): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pending_uploads WHERE user_id = ?", Int::class.java, uuidToBytes(userId)) ?: 0

    private fun tournamentItemCount(tournamentId: Long): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tournament_items WHERE tournament_id = ?", Int::class.java, tournamentId) ?: 0

    private fun cleanupWishes(userId: UUID) {
        val itemIds =
            jdbcTemplate.queryForList(
                "SELECT s.item_id FROM wishes w JOIN item_snapshots s ON s.id = w.snapshot_id WHERE w.user_id = ?",
                Long::class.java,
                uuidToBytes(userId),
            )
        jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(userId))
        itemIds.takeIf { it.isNotEmpty() }?.let {
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id IN (${it.joinToString(",")})")
            jdbcTemplate.update("DELETE FROM items WHERE id IN (${it.joinToString(",")})")
        }
        jdbcTemplate.update("DELETE FROM pending_uploads WHERE user_id = ?", uuidToBytes(userId))
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }

    private fun cleanupTournament(
        ownerId: UUID,
        tournamentId: Long,
    ) {
        if (tournamentId != 0L) {
            val itemIds =
                jdbcTemplate.queryForList(
                    "SELECT s.item_id FROM tournament_items ti JOIN item_snapshots s ON s.id = ti.snapshot_id WHERE ti.tournament_id = ?",
                    Long::class.java,
                    tournamentId,
                )
            jdbcTemplate.update("DELETE FROM tournament_items WHERE tournament_id = ?", tournamentId)
            jdbcTemplate.update("DELETE FROM tournament_users WHERE tournament_id = ?", tournamentId)
            jdbcTemplate.update("DELETE FROM tournaments WHERE id = ?", tournamentId)
            itemIds.takeIf { it.isNotEmpty() }?.let {
                jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id IN (${it.joinToString(",")})")
                jdbcTemplate.update("DELETE FROM items WHERE id IN (${it.joinToString(",")})")
            }
        }
        jdbcTemplate.update("DELETE FROM pending_uploads WHERE user_id = ?", uuidToBytes(ownerId))
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(ownerId))
    }
}
