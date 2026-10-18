package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemLinkJpaRepository
import com.depromeet.piki.item.repository.ItemLinkRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.product.domain.CanonicalLink
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 상품 정체성 기록(#825 관측 단계)의 DB 계약 검증 — 별칭 INSERT IGNORE·조건부 canonical claim·충돌 관측은
// 전부 DB 문장(unique·조건부 UPDATE)의 실제 동작이라 단위로 내릴 수 없다.
//
// 클래스 @Transactional 을 쓰지 않는다 — 운영에서 recordParsingIdentity 는 호출마다 자기 트랜잭션(REQUIRED)으로
// 돌고, 조건부 UPDATE 는 native 라 영속성 컨텍스트를 우회한다. 테스트가 트랜잭션을 공유하면 recorder 내부의
// 재조회(findById)가 stale 1차 캐시를 읽어 운영과 다른 분기(already_same 이 drift 로 오판)를 탄다.
// 대신 각 테스트가 자기 행을 메서드 끝에서 명시 정리하고, 테스트 간 충돌이 없게 URL 을 테스트별로 달리 쓴다
// (동시성 통합 테스트와 같은 격리 방식).
class ItemIdentityRecordingIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var itemIdentityRecorder: ItemIdentityRecorder

    @Autowired
    private lateinit var itemRepository: ItemRepository

    @Autowired
    private lateinit var itemLinkRepository: ItemLinkRepository

    @Autowired
    private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired
    private lateinit var itemLinkJpaRepository: ItemLinkJpaRepository

    @Autowired
    private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired
    private lateinit var wishJpaRepository: WishJpaRepository

    private fun newItem(url: String): Item = itemRepository.save(Item(link = ProductLink.parse(url)))

    private fun cleanup(itemIds: List<Long>) {
        itemIds.forEach { id ->
            itemLinkJpaRepository.deleteAll(itemLinkJpaRepository.findByItemIdAndDeletedAtIsNull(id))
            itemJpaRepository.deleteById(id)
        }
    }

    @Test
    fun `등록 별칭은 정규화된 입력으로 기록된다 - 추적 파라미터가 떨어진 형태`() {
        val item = newItem("https://www.musinsa.com/products/1000001?utm_source=kakao&af_channel=share")
        try {
            itemIdentityRecorder.recordRegistrationAlias(item)

            val expected = CanonicalLink.of(ProductLink.parse("https://www.musinsa.com/products/1000001"))
            val alias = itemLinkRepository.findByUrlHash(expected.hash)
            assertNotNull(alias)
            assertEquals(item.getId(), alias.itemId)
            assertEquals(expected.url, alias.url)
        } finally {
            cleanup(listOf(item.getId()))
        }
    }

    @Test
    fun `같은 문자열 재등록은 별칭을 덮지 않고 첫 item 소속으로 남는다 - 등록은 죽지 않는다`() {
        val first = newItem("https://www.musinsa.com/products/1000002")
        val second = newItem("https://www.musinsa.com/products/1000002")
        try {
            itemIdentityRecorder.recordRegistrationAlias(first)
            itemIdentityRecorder.recordRegistrationAlias(second)

            val hash = CanonicalLink.of(ProductLink.parse("https://www.musinsa.com/products/1000002")).hash
            val alias = itemLinkRepository.findByUrlHash(hash)
            assertNotNull(alias)
            assertEquals(first.getId(), alias.itemId)
            // 공유 활성화 전이라 두 번째 등록도 자기 item 을 그대로 갖는다(관측만).
            assertTrue(itemLinkRepository.findByItemId(second.getId()).isEmpty())
        } finally {
            cleanup(listOf(first.getId(), second.getId()))
        }
    }

    @Test
    fun `파싱 완료 시 귀결점으로 canonical 이 확정되고 귀결점 별칭이 남는다`() {
        val item = newItem("https://musinsa.onelink.me/PvkC/idrec0001")
        try {
            itemIdentityRecorder.recordParsingIdentity(
                item.getId(),
                "https://www.musinsa.com/products/1000003?af_referrer_customer_id=jsy0714&shortlink=idrec0001",
            )

            val expected = CanonicalLink.of(ProductLink.parse("https://www.musinsa.com/products/1000003"))
            val reloaded = itemRepository.findById(item.getId())
            assertNotNull(reloaded)
            assertEquals(expected.url, reloaded.canonicalUrl)
            assertEquals(expected.hash, reloaded.canonicalHash)
            val alias = itemLinkRepository.findByUrlHash(expected.hash)
            assertNotNull(alias)
            assertEquals(item.getId(), alias.itemId)
        } finally {
            cleanup(listOf(item.getId()))
        }
    }

    @Test
    fun `같은 귀결점 재확정은 멱등이고 다른 귀결점은 첫 확정을 유지한다 - 정체성 불변`() {
        val item = newItem("https://www.29cm.co.kr/products/1000004")
        try {
            itemIdentityRecorder.recordParsingIdentity(item.getId(), "https://www.29cm.co.kr/products/1000004?reward_key=RK_AAAA")
            val firstHash = itemRepository.findById(item.getId())?.canonicalHash
            assertNotNull(firstHash)

            // 같은 값 재확정(재파싱) — 멱등.
            itemIdentityRecorder.recordParsingIdentity(item.getId(), "https://www.29cm.co.kr/products/1000004?reward_key=RK_BBBB")
            assertEquals(firstHash, itemRepository.findById(item.getId())?.canonicalHash)

            // 다른 귀결점(드리프트) — 첫 확정 유지.
            itemIdentityRecorder.recordParsingIdentity(item.getId(), "https://www.29cm.co.kr/products/9999994")
            assertEquals(firstHash, itemRepository.findById(item.getId())?.canonicalHash)
        } finally {
            cleanup(listOf(item.getId()))
        }
    }

    @Test
    fun `다른 item 이 같은 귀결점을 소유하면 병합 후보로 관측만 하고 확정하지 않는다`() {
        val owner = newItem("https://musinsa.onelink.me/PvkC/idrec0002")
        val latecomer = newItem("https://musinsa.onelink.me/PvkC/idrec0003")
        val finalUrl = "https://www.musinsa.com/products/1000005"
        try {
            itemIdentityRecorder.recordParsingIdentity(owner.getId(), finalUrl)
            itemIdentityRecorder.recordParsingIdentity(latecomer.getId(), finalUrl)

            val expectedHash = CanonicalLink.of(ProductLink.parse(finalUrl)).hash
            assertEquals(expectedHash, itemRepository.findById(owner.getId())?.canonicalHash)
            // 공유 활성화 전 — 병합하지 않고 미확정으로 남긴다.
            assertNull(itemRepository.findById(latecomer.getId())?.canonicalHash)
            // 귀결점 별칭은 먼저 확정한 쪽 소속 그대로다.
            assertEquals(owner.getId(), itemLinkRepository.findByUrlHash(expectedHash)?.itemId)
        } finally {
            cleanup(listOf(owner.getId(), latecomer.getId()))
        }
    }

    @Test
    fun `병합되면 진 item 을 가리키던 위시의 item_id 가 이긴 item 으로 따라간다`() {
        // 위시는 item 을 직접 참조하므로(#1051) 버전 재부모화만으로는 참조가 안 따라간다 — 병합 트랜잭션 안에서
        // ItemMerged 를 위시가 받아 옮긴다. 이게 빠지면 2단계(item_id 읽기 전환)에서 위시가 soft delete 된 item 을 가리킨다.
        val owner = newItem("https://musinsa.onelink.me/PvkC/idrec0010")
        val latecomer = newItem("https://musinsa.onelink.me/PvkC/idrec0011")
        val finalUrl = "https://www.musinsa.com/products/1000010"
        val userId = UUID.randomUUID()
        val snapshot = itemSnapshotJpaRepository.save(ItemSnapshot.pending(latecomer.getId(), requestedBy = userId))
        val wish =
            wishJpaRepository.save(Wish(userId = userId, waitingSnapshotId = snapshot.getId(), itemId = latecomer.getId()))
        try {
            itemIdentityRecorder.recordParsingIdentity(owner.getId(), finalUrl)
            itemIdentityRecorder.recordParsingIdentity(latecomer.getId(), finalUrl)

            assertEquals(owner.getId(), wishJpaRepository.findById(wish.getId()).orElseThrow().itemId)
            assertEquals(owner.getId(), itemSnapshotJpaRepository.findById(snapshot.getId()).orElseThrow().itemId)
        } finally {
            wishJpaRepository.deleteById(wish.getId())
            itemSnapshotJpaRepository.deleteById(snapshot.getId())
            cleanup(listOf(owner.getId(), latecomer.getId()))
        }
    }

    @Test
    fun `finalUrl 이 없거나 파싱 불가하면 canonical 은 미확정으로 남는다 - 구버전 extractor 호환`() {
        val item = newItem("https://www.musinsa.com/products/1000006")
        try {
            itemIdentityRecorder.recordParsingIdentity(item.getId(), null)
            assertNull(itemRepository.findById(item.getId())?.canonicalHash)

            itemIdentityRecorder.recordParsingIdentity(item.getId(), "ftp://insecure.example.com/p/1")
            assertNull(itemRepository.findById(item.getId())?.canonicalHash)
        } finally {
            cleanup(listOf(item.getId()))
        }
    }

    @Test
    fun `http 귀결점은 같은 주소의 https 와 같은 정체성으로 확정된다`() {
        val item = newItem("https://unknown-mall.example.com/p/1000008")
        try {
            itemIdentityRecorder.recordParsingIdentity(item.getId(), "http://unknown-mall.example.com/product/1000008")

            val expected = CanonicalLink.of(ProductLink.parse("https://unknown-mall.example.com/product/1000008"))
            assertEquals(expected.hash, itemRepository.findById(item.getId())?.canonicalHash)
        } finally {
            cleanup(listOf(item.getId()))
        }
    }

    @Test
    fun `저장 상한을 넘는 귀결점은 확정·별칭 모두 건너뛴다 - 절단은 거짓 정체성`() {
        val item = newItem("https://unknown-mall.example.com/p/1000007")
        try {
            val oversize = "https://unknown-mall.example.com/product/" + "%EC%A0%80".repeat(300)
            itemIdentityRecorder.recordParsingIdentity(item.getId(), oversize)

            assertNull(itemRepository.findById(item.getId())?.canonicalHash)
            assertTrue(itemLinkRepository.findByItemId(item.getId()).isEmpty())
        } finally {
            cleanup(listOf(item.getId()))
        }
    }
}
