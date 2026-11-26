package com.depromeet.piki.explore.repository

import com.depromeet.piki.explore.domain.ExploreCard
import com.depromeet.piki.explore.domain.ExploreFeed
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
class ExploreFeedSerializationCompatibilityTest : IntegrationTestSupport() {
    @Autowired private lateinit var exploreFeedStore: ExploreFeedStore

    @Autowired private lateinit var redisTemplate: StringRedisTemplate

    private val objectMapper = ObjectMapper()

    @Test
    fun `앞 버전이 저장한 피드를 그대로 읽는다`() {
        redisTemplate.opsForValue().set(ExploreFeedStore.RECENT_KEY, snapshot())

        assertEquals(expected(), exploreFeedStore.find())
    }

    // 필드를 지우면 읽기는 통과해도 앞 버전이 새 값을 못 읽는다. 쓰는 모양도 스냅샷에 묶는다
    @Test
    fun `지금 저장하는 모양이 앞 버전이 저장한 모양과 같다`() {
        exploreFeedStore.save(expected())

        val written = redisTemplate.opsForValue().get(ExploreFeedStore.RECENT_KEY)
        assertEquals(objectMapper.readTree(snapshot()), objectMapper.readTree(written))
    }

    @Test
    fun `읽을 수 없는 값은 없는 것으로 본다`() {
        redisTemplate.opsForValue().set(ExploreFeedStore.RECENT_KEY, """{"cards":"깨진 값"}""")

        assertNull(exploreFeedStore.find())
    }

    private fun snapshot(): String = ClassPathResource("explore/explore-feed-v1.json").getContentAsString(Charsets.UTF_8)

    private fun expected(): ExploreFeed =
        ExploreFeed(
            builtAt = LocalDateTime.of(2026, 11, 23, 10, 0),
            cards =
                listOf(
                    ExploreCard(
                        wishId = 1024,
                        ownerId = UUID.fromString("3f2b8c1e-5d47-4a9b-8e21-6c0d9f7a1b23"),
                        itemId = 512,
                        addedAt = LocalDateTime.of(2026, 11, 23, 9, 58, 30),
                        name = "에어 조던 1 미드",
                        price = 119_000,
                        originalPrice = 139_000,
                        discountRate = 14,
                        currency = "KRW",
                        imageUrl = "https://cdn.example.com/p/512.jpg",
                        sourceUrl = "https://www.example-shop.com/products/12345",
                        sourcePlatform = "29CM",
                    ),
                    ExploreCard(
                        wishId = 1023,
                        ownerId = UUID.fromString("9a7d4e02-1c3b-4f58-b6a9-2e8f0c5d7141"),
                        itemId = 498,
                        addedAt = LocalDateTime.of(2026, 11, 23, 9, 41, 2),
                        name = "직접 찍은 가방",
                        price = 45_000,
                        originalPrice = null,
                        discountRate = null,
                        currency = null,
                        imageUrl = "https://cdn.example.com/u/498.jpg",
                        sourceUrl = null,
                        sourcePlatform = null,
                    ),
                ),
        )
}
