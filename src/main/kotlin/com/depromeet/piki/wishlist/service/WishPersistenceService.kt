package com.depromeet.piki.wishlist.service

import com.depromeet.piki.image.domain.PendingUploadContext
import com.depromeet.piki.image.service.PendingUploadClaimer
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.repository.WishRepository
import com.depromeet.piki.wishlist.service.dto.WishWithItem
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// WishlistService 의 registerFromUrl 가 외부 LLM 호출을 트랜잭션 바깥에 두도록
// 영속화만 별도 빈으로 분리. 같은 빈에서 호출하면 Spring AOP proxy 를
// 거치지 않아 @Transactional 가 무력화되기 때문이다.
//
// item 은 정체성(link)만 들고 추출값·상태는 ItemSnapshot 이 보유한다. URL 등록 경로는 link 만 가진 item 과
// PENDING snapshot(outbox 적재)을 같은 트랜잭션에서 함께 저장하고, wish 가 그 snapshot 을 활성 포인터로 가리킨다.
// 파싱은 디스패처(@Scheduled)가 PENDING 을 집어 시작하므로, 여기선 워커를 트리거하지 않는다.
@Service
class WishPersistenceService(
    private val wishRepository: WishRepository,
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val pendingUploadClaimer: PendingUploadClaimer,
) {
    // item(정체성) → snapshot(PENDING 버전) → wish 순서로 같은 트랜잭션에서 저장한다.
    // item 생성은 호출부가 트랜잭션 바깥에서 끝내고, 여기선 영속화만 한다.
    // snapshot 을 PENDING 으로 커밋하는 것이 곧 outbox 적재다 — 디스패처가 이 행을 집어 PROCESSING 으로 claim 한다.
    @Transactional
    fun persist(
        userId: UUID,
        item: Item,
    ): WishWithItem {
        val saved = itemRepository.save(item)
        // 저장한 snapshot 의 id 를 wish 의 활성 포인터(snapshotId)로 박는다. 5단계 갱신에서 새 버전으로 스왑된다.
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(saved.getId()))
        val wish = wishRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))
        return WishWithItem(wish = wish, item = saved, snapshot = snapshot)
    }

    // v1(multipart) 이미지 다건 등록 — 서버가 바이트를 받아 S3 에 올린 뒤 pending 매핑 없이 바로 적재한다.
    // 입력(imageKey)이 행에 박혀 durable 하므로 link 경로와 같은 outbox 에 적재한다 — 디스패처가 PENDING 을 집어 워커에 넘긴다.
    @Transactional
    fun persistPendingImages(
        userId: UUID,
        imageKeys: List<String>,
    ): List<WishWithItem> = persistImagesInternal(userId, imageKeys)

    // v2 이미지 등록 — confirm 또는 폴링 백스톱이 "업로드 확인된" key 들을 등록한다. pending_uploads 를 FOR UPDATE 로
    // 잠가 삭제(claim)하고, claim 에 성공한(=이 트랜잭션이 가져간) WISH 매핑만 적재한다 — confirm·폴링이 같은 key 를
    // 다퉈도 삭제는 한쪽만 성공하므로 중복 등록되지 않는다(멱등). 다른 user·토너먼트 맥락 매핑은 걸러낸다.
    @Transactional
    fun registerClaimedImages(
        imageKeys: List<String>,
        userId: UUID,
    ): List<WishWithItem> {
        val claimedKeys = pendingUploadClaimer.claim(imageKeys, PendingUploadContext.WISH, userId, tournamentId = null)
        if (claimedKeys.isEmpty()) return emptyList()
        return persistImagesInternal(userId, claimedKeys)
    }

    // 이미지 key 들을 item(정체성) → PENDING snapshot(outbox 적재) → wish 순서로 배치 적재하는 공통 코어.
    // 트랜잭션은 호출부(persistPendingImages·registerClaimedImages)가 연다 — self-invocation 으로 트랜잭션이 무력화되지 않게 private.
    private fun persistImagesInternal(
        userId: UUID,
        imageKeys: List<String>,
    ): List<WishWithItem> {
        val items = itemRepository.saveAll(imageKeys.map { Item(sourceImageKey = it) })
        // snapshot 을 itemId 로 매핑해 saveAll 반환 순서에 의존하지 않는다(순서 보존은 공식 계약이 아니다).
        val snapshotsByItemId =
            itemSnapshotRepository.saveAll(items.map { ItemSnapshot.pending(it.getId()) }).associateBy { it.itemId }
        return items.map { item ->
            val snapshot = snapshotsByItemId[item.getId()] ?: error("item ${item.getId()} 의 snapshot 이 없다")
            val wish = wishRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))
            WishWithItem(wish = wish, item = item, snapshot = snapshot)
        }
    }

    // FAILED 버전의 수동 보정 영속화 — S3 업로드(외부 호출)는 호출부가 트랜잭션 바깥에서 끝내고,
    // 여기선 snapshot.recover(값 변경 + FAILED→READY 전이)만 짧은 트랜잭션으로 묶는다(dirty checking).
    // recover 가 READY/PROCESSING 을 409, 이름 없음을 400 으로 막는다(도메인 자기방어). item 은 정체성이라 건드리지 않는다.
    // 호출부가 검증한 그 snapshot 을 id 로 직접 보정한다 — findLatestByItemId(최신)가 아니다. 갱신(refresh)이 끼어들어
    // 활성≠최신이 되어도, 검증한 행과 보정 대상 행이 어긋나지 않는다(refresh-vs-recover race 로 엉뚱한 버전 보정·409 방지).
    @Transactional
    fun recoverItem(
        snapshotId: Long,
        name: String?,
        currentPrice: Int?,
        imageUrl: String?,
        currency: String?,
    ): Item {
        val snapshot =
            itemSnapshotRepository.findById(snapshotId)
                ?: error("snapshot $snapshotId 이 없다")
        val item = itemRepository.findById(snapshot.itemId) ?: error("item ${snapshot.itemId} 가 없다")
        snapshot.recover(name = name, currentPrice = currentPrice, imageUrl = imageUrl, currency = currency)
        return item
    }

    // 위시 item 을 원본 링크로 재추출해 최신화한다(수동 새로고침). 새 PENDING snapshot 을 outbox 에 적재하고
    // wish 활성 포인터를 즉시 그 버전으로 스왑한다 — 디스패처가 PENDING 을 집어 추출해 READY/FAILED 로 전이한다(등록과 동일 흐름).
    // 옛 snapshot 행은 유지돼 토너먼트 출전 격리를 지킨다. 외부 호출(추출)은 디스패처가 트랜잭션 밖에서 하므로 여기선 적재만 한다.
    // 동시 새로고침은 wish 행 락(findByIdForUpdate)으로 직렬화하고, 이미 진행 중이면 멱등(no-op)으로 새 추출을 만들지 않는다.
    @Transactional
    fun refresh(
        userId: UUID,
        wishId: Long,
    ): WishWithItem {
        val wish = wishRepository.findByIdForUpdate(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        // item 정체성은 snapshot.itemId 단일 출처. snapshot·item 은 영속화 경로상 반드시 존재한다(없으면 코드 버그).
        val activeSnapshot =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        val item =
            itemRepository.findById(activeSnapshot.itemId)
                ?: error("wish ${wish.getId()} 의 item ${activeSnapshot.itemId} 가 없다")
        // link 없는 item(이미지 등록분)은 재추출 입력이 없어 새로고침 대상이 아니다(400).
        item.link ?: throw WishException.notRefreshable()
        // 이미 진행 중(PENDING·PROCESSING)이면 새 추출을 만들지 않고 현재 진행 상태를 그대로 반환(멱등).
        if (activeSnapshot.isInProgress()) return WishWithItem(wish = wish, item = item, snapshot = activeSnapshot)
        // 추출 실패(FAILED) 항목은 새로고침 대상이 아니다 — 보정(recover)으로 복구한다(409). 새로고침은 성공(READY)
        // 항목의 재추출 전용이라, 보정(FAILED 대상)과 상태로 갈려 recover-vs-refresh 동시 요청이 서로의 활성 포인터를
        // 침범하지 않는다(보정 진행 중엔 FAILED 라 새로고침이 여기서 막혀, 보정이 끝나기 전 활성이 스왑되지 않는다).
        if (activeSnapshot.isFailed()) throw WishException.failedNotRefreshable()
        // 새 PENDING 버전을 outbox 에 적재하고 활성 포인터를 즉시 스왑한다.
        val newSnapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()))
        wish.swapSnapshot(newSnapshot.getId())
        return WishWithItem(wish = wish, item = item, snapshot = newSnapshot)
    }
}
