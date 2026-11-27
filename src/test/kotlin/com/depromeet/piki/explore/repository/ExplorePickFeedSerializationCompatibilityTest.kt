package com.depromeet.piki.explore.repository

import com.depromeet.piki.explore.domain.ExploreItem
import com.depromeet.piki.explore.domain.ExplorePick
import com.depromeet.piki.explore.domain.ExplorePickFeed
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 배포 중에는 앞 버전이 써 둔 피드를 새 버전이 읽는다. 스냅샷이 깨지면 필드를 nullable·기본값으로 더했는지 본다
class ExplorePickFeedSerializationCompatibilityTest : IntegrationTestSupport() {
    @Autowired private lateinit var exploreFeedStore: ExploreFeedStore

    @Autowired private lateinit var redisTemplate: StringRedisTemplate

    private val objectMapper = ObjectMapper()

    @Test
    fun `앞 버전이 저장한 1위 피드를 그대로 읽는다`() {
        redisTemplate.opsForValue().set(ExploreFeedStore.PICKS_KEY, snapshot())

        assertEquals(expected(), exploreFeedStore.findPicks())
    }

    @Test
    fun `지금 저장하는 모양이 앞 버전이 저장한 모양과 같다`() {
        exploreFeedStore.savePicks(expected())

        val written = redisTemplate.opsForValue().get(ExploreFeedStore.PICKS_KEY)
        assertEquals(objectMapper.readTree(snapshot()), objectMapper.readTree(written))
    }

    @Test
    fun `읽을 수 없는 값은 없는 것으로 본다`() {
        redisTemplate.opsForValue().set(ExploreFeedStore.PICKS_KEY, """{"picks":"깨진 값"}""")

        assertNull(exploreFeedStore.findPicks())
    }

    private fun snapshot(): String = ClassPathResource("explore/explore-picks-v1.json").getContentAsString(Charsets.UTF_8)

    private fun expected(): ExplorePickFeed =
        ExplorePickFeed(
            builtAt = LocalDateTime.of(2026, 11, 27, 10, 0),
            picks =
                listOf(
                    ExplorePick(
                        pickId = 2048,
                        pickerId = UUID.fromString("3f2b8c1e-5d47-4a9b-8e21-6c0d9f7a1b23"),
                        tournamentId = 301,
                        tournamentName = "겨울 패딩 뭐 사지",
                        candidateId = 4096,
                        pickedAt = LocalDateTime.of(2026, 11, 27, 9, 58, 30),
                        item =
                            ExploreItem(
                                itemId = 512,
                                name = "숏 패딩 자켓",
                                price = 159_000,
                                originalPrice = 199_000,
                                discountRate = 20,
                                currency = "KRW",
                                imageUrl = "https://cdn.example.com/p/512.jpg",
                                sourceUrl = "https://www.example-shop.com/products/12345",
                                sourcePlatform = "29CM",
                            ),
                    ),
                    ExplorePick(
                        pickId = 2047,
                        pickerId = UUID.fromString("9a7d4e02-1c3b-4f58-b6a9-2e8f0c5d7141"),
                        tournamentId = 300,
                        tournamentName = "생일 선물",
                        candidateId = 4090,
                        pickedAt = LocalDateTime.of(2026, 11, 27, 9, 41, 2),
                        item =
                            ExploreItem(
                                itemId = 498,
                                name = "캔버스 토트백",
                                price = 45_000,
                                originalPrice = null,
                                discountRate = null,
                                currency = null,
                                imageUrl = "https://cdn.example.com/u/498.jpg",
                                sourceUrl = null,
                                sourcePlatform = null,
                            ),
                    ),
                ),
        )
}
