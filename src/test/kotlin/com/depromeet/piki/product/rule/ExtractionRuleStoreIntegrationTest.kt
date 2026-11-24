package com.depromeet.piki.product.rule

import com.depromeet.piki.contracts.extraction.v1.ExtractionRule
import com.depromeet.piki.contracts.extraction.v1.ExtractionRuleSet
import com.depromeet.piki.support.IntegrationTestSupport
import com.google.protobuf.util.JsonFormat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 규칙 원본(DB)과 extractor 가 읽는 배포본(Redis)의 관계를 고정한다.
// @Transactional 자동 롤백을 쓰지 않는다 — 배포본은 쓰기 트랜잭션이 끝난 뒤에 올라가고 Redis 는 롤백되지 않는다.
// 배포본은 테이블 전체로 만들어지므로 이 클래스는 두 테이블과 키를 통째로 쓴다. 각 테스트가 끝에서 비운다(finally).
class ExtractionRuleStoreIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var store: ExtractionRuleStore

    @Autowired
    private lateinit var publisher: ExtractionRulePublisher

    @Autowired
    private lateinit var ruleRepository: ExtractionRuleJpaRepository

    @Autowired
    private lateinit var revisionRepository: ExtractionRuleRevisionJpaRepository

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private fun command(
        ruleJson: String,
        label: String = "올리브영",
        enabled: Boolean = true,
    ) = SaveExtractionRuleCommand(
        label = label,
        ruleJson = ruleJson,
        sampleUrl = "https://www.oliveyoung.co.kr/store/goods/getGoodsDetail.do?goodsNo=A000000171788",
        enabled = enabled,
        actor = "tester",
    )

    // extractor 와 같은 방식으로 읽는다 — 계약 클래스로 해석되지 않는 값이 올라가면 여기서 깨진다.
    private fun published(): ExtractionRuleSet {
        val raw = redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY) ?: error("배포본 키가 없다")
        val builder = ExtractionRuleSet.newBuilder()
        JsonFormat.parser().merge(raw, builder)
        return builder.build()
    }

    private fun cleanUp() {
        revisionRepository.deleteAll()
        ruleRepository.deleteAll()
        redisTemplate.delete(ExtractionRulePublisher.KEY)
    }

    @Test
    fun `규칙을 저장하면 커밋 뒤 켜진 규칙 전체가 계약 모양으로 Redis 에 올라간다`() {
        try {
            val oliveYoung =
                store.create(
                    command(
                        """
                        {"host": "oliveyoung.co.kr", "fetch": "RENDER",
                         "currentPrice": {"css": {"selector": "[class^='GoodsDetailInfo_price__'] span"}},
                         "absentOriginalPrice": "NO_DISCOUNT"}
                        """.trimIndent(),
                    ),
                )
            val twentyNine =
                store.create(command("""{"host": "29cm.co.kr", "absentOriginalPrice": "NO_DISCOUNT"}""", label = "29CM"))
            store.create(command("""{"marker": "meta[name='generator']"}""", label = "꺼 둔 규칙", enabled = false))

            val set = published()

            assertEquals(listOf(oliveYoung, twentyNine), set.rulesList.map { it.id })
            assertEquals(revisionRepository.lastRevisionId(), set.version)
            val first = set.rulesList.first()
            assertEquals("oliveyoung.co.kr", first.host)
            assertEquals(ExtractionRule.FetchMode.RENDER, first.fetch)
            assertEquals("[class^='GoodsDetailInfo_price__'] span", first.currentPrice.css.selector)
            assertEquals(ExtractionRule.AbsentOriginalPrice.NO_DISCOUNT, first.absentOriginalPrice)
            assertNull(redisTemplate.getExpire(ExtractionRulePublisher.KEY).takeIf { it >= 0 }, "배포본 키에는 만료를 걸지 않는다")
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `규칙을 끄거나 지우면 배포본에서 빠지고 판 번호는 커진다`() {
        try {
            val kept = store.create(command("""{"host": "29cm.co.kr"}""", label = "29CM"))
            val disabled = store.create(command("""{"host": "oliveyoung.co.kr"}"""))
            val deleted = store.create(command("""{"host": "musinsa.com"}""", label = "무신사"))
            val versionBefore = published().version

            store.update(disabled, command("""{"host": "oliveyoung.co.kr"}""", enabled = false))
            store.delete(deleted, "tester")

            val set = published()
            assertEquals(listOf(kept), set.rulesList.map { it.id })
            assertTrue(set.version > versionBefore)
            assertFalse(ruleRepository.existsById(deleted))
            assertEquals(
                listOf("SAVED", "DELETED"),
                revisionRepository.findAll().filter { it.extractionRuleId == deleted }.sortedBy { it.getId() }.map { it.changeType },
            )
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `배포본 키가 지워지면 대조가 다시 올리고 같으면 다시 쓰지 않는다`() {
        try {
            store.create(command("""{"host": "29cm.co.kr", "absentOriginalPrice": "NO_DISCOUNT"}""", label = "29CM"))
            val before = redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY)
            assertFalse(publisher.publish(), "DB 와 같은 배포본은 다시 쓰지 않는다")

            redisTemplate.delete(ExtractionRulePublisher.KEY)
            publisher.reconcile()

            assertEquals(before, redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY))

            redisTemplate.opsForValue().set(ExtractionRulePublisher.KEY, """{"version": 1, "rules": []}""")
            publisher.reconcile()

            assertEquals(before, redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY))
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `배포본은 DB 만으로 정해지는 문자열이고 손으로 넣은 깨진 행은 그 행만 빠진다`() {
        try {
            val id = store.create(command("""{"host": "29cm.co.kr", "absentOriginalPrice": "NO_DISCOUNT"}""", label = "29CM"))
            ruleRepository.save(ExtractionRuleEntity("손으로 넣은 행", "[1, 2", "https://example.com/p/1", true))

            publisher.reconcile()

            assertEquals(
                """{"version":${revisionRepository.lastRevisionId()},"rules":[""" +
                    """{"host":"29cm.co.kr","absentOriginalPrice":"NO_DISCOUNT","id":$id}]}""",
                redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY),
            )
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `규칙이 하나도 없으면 빈 배포본을 올린다`() {
        try {
            publisher.reconcile()

            val set = published()
            assertEquals(0, set.rulesCount)
            assertEquals(revisionRepository.lastRevisionId(), set.version)
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `읽지 못하는 규칙은 저장하지 않고 이력도 배포본도 바뀌지 않는다`() {
        try {
            val existing = store.create(command("""{"host": "29cm.co.kr"}""", label = "29CM"))
            val publishedBefore = redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY)
            val revisionsBefore = revisionRepository.count()

            assertFailsWith<IllegalArgumentException> { store.create(command("""{"host": "oliveyoung.co.kr", "curentPrice": {}}""")) }
            assertFailsWith<IllegalArgumentException> { store.create(command("""{"absentOriginalPrice": "NO_DISCOUNT"}""")) }
            assertFailsWith<IllegalArgumentException> { store.update(existing, command("""{"host": """)) }
            assertFailsWith<IllegalArgumentException> { store.create(command("""{"host": "oliveyoung.co.kr"}""", label = " ")) }
            assertFailsWith<IllegalArgumentException> { store.delete(existing + 1_000, "tester") }

            assertEquals(1, ruleRepository.count())
            assertEquals(revisionsBefore, revisionRepository.count())
            assertEquals(publishedBefore, redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY))
        } finally {
            cleanUp()
        }
    }
}
